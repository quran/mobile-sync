package com.quran.shared.persistence.repository.bookmark.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.touchlab.kermit.Logger
import com.quran.shared.di.AppScope
import com.quran.shared.mutations.LOCAL_MUTATION_BOOKMARK_ENTITY_FACET
import com.quran.shared.mutations.LOCAL_MUTATION_BOOKMARK_READING_FACET
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.LocalMutationAck
import com.quran.shared.mutations.LocalMutationResource
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.input.RemoteBookmark
import com.quran.shared.persistence.model.AyahBookmark
import com.quran.shared.persistence.model.DEFAULT_COLLECTION_ID
import com.quran.shared.persistence.model.DatabaseBookmark
import com.quran.shared.persistence.repository.PersistenceWriteBoundaryGuard
import com.quran.shared.persistence.repository.buildRemoteResourceExistenceMap
import com.quran.shared.persistence.repository.bookmark.BookmarkDependencyReconciler
import com.quran.shared.persistence.repository.bookmark.extension.toAyahBookmark
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.QuranData
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toEpochMillisecondsOrNull
import com.quran.shared.persistence.util.toPlatform
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Inject
@SingleIn(AppScope::class)
class BookmarksRepositoryImpl(
    private val database: QuranDatabase,
    private val reconciler: BookmarkDependencyReconciler = BookmarkDependencyReconciler(database)
) : BookmarksRepository, BookmarksSynchronizationRepository {

    private val logger = Logger.withTag("BookmarksRepository")
    private val bookmarkQueries = lazy { database.bookmarksQueries }
    private val bookmarkCollectionQueries = lazy { database.bookmark_collectionsQueries }
    private val collectionQueries = lazy { database.collectionsQueries }

    override suspend fun getAllBookmarks(): List<AyahBookmark> {
        return withContext(Dispatchers.IO) {
            bookmarkQueries.value.getSavedAyahBookmarks()
                .executeAsList()
                .map { it.toAyahBookmark() }
        }
    }

    override fun getBookmarksFlow(): Flow<List<AyahBookmark>> {
        return bookmarkQueries.value.getSavedAyahBookmarks()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list -> list.map { it.toAyahBookmark() } }
    }

    override suspend fun addBookmark(sura: Int, ayah: Int): AyahBookmark {
        return addBookmark(sura = sura, ayah = ayah, collectionLocalIds = null)
    }

    override suspend fun addBookmark(sura: Int, ayah: Int, timestamp: PlatformDateTime): AyahBookmark {
        return addBookmark(
            sura = sura,
            ayah = ayah,
            collectionLocalIds = null,
            timestamp = timestamp
        )
    }

    override suspend fun addBookmark(
        sura: Int,
        ayah: Int,
        collectionLocalIds: List<String>?
    ): AyahBookmark {
        return addBookmarkWithTimestampMillis(
            sura = sura,
            ayah = ayah,
            collectionLocalIds = collectionLocalIds,
            timestampMillis = null
        )
    }

    override suspend fun addBookmark(
        sura: Int,
        ayah: Int,
        collectionLocalIds: List<String>?,
        timestamp: PlatformDateTime
    ): AyahBookmark {
        return addBookmarkWithTimestampMillis(
            sura = sura,
            ayah = ayah,
            collectionLocalIds = collectionLocalIds,
            timestampMillis = timestamp.toEpochMillisecondsOrNull()
        )
    }

    private suspend fun addBookmarkWithTimestampMillis(
        sura: Int,
        ayah: Int,
        collectionLocalIds: List<String>?,
        timestampMillis: Long?
    ): AyahBookmark {
        logger.i { "Adding ayah bookmark for $sura:$ayah" }
        return withContext(Dispatchers.IO) {
            var created: AyahBookmark? = null
            database.transaction {
                val normalizedCollectionIds = normalizeCollectionIds(collectionLocalIds)
                val ayahId = getAyahId(sura, ayah).toLong()

                bookmarkQueries.value.upsertAyahBookmark(
                    remote_id = null,
                    ayah_id = ayahId,
                    sura = sura.toLong(),
                    ayah = ayah.toLong(),
                    timestamp = timestampMillis
                )

                if (DEFAULT_COLLECTION_ID in normalizedCollectionIds) {
                    bookmarkQueries.value.addAyahToDefaultCollection(
                        ayah_id = ayahId,
                        sura = sura.toLong(),
                        ayah = ayah.toLong(),
                        timestamp = timestampMillis
                    )
                }

                val bookmarkRecord = requireNotNull(
                    bookmarkQueries.value.getBookmarkForAyah(sura.toLong(), ayah.toLong()).executeAsOneOrNull()
                ) { "Expected ayah bookmark for $sura:$ayah after insert." }

                normalizedCollectionIds
                    .filterNot { it == DEFAULT_COLLECTION_ID }
                    .forEach { collectionLocalId ->
                        val collection = collectionQueries.value
                            .getCollectionByLocalId(collectionLocalId.toLong())
                            .executeAsOneOrNull()
                        requireNotNull(collection) { "Collection not found for localId=$collectionLocalId." }
                        bookmarkCollectionQueries.value.addBookmarkToCollection(
                            bookmark_local_id = bookmarkRecord.local_id,
                            collection_local_id = collection.local_id,
                            timestamp = timestampMillis
                        )
                    }

                reconciler.reconcile()
                created = bookmarkQueries.value
                    .getBookmarkForAyah(sura.toLong(), ayah.toLong())
                    .executeAsOne()
                    .toAyahBookmark()
            }
            requireNotNull(created)
        }
    }

    override suspend fun deleteBookmark(sura: Int, ayah: Int): Boolean {
        logger.i { "Deleting ayah bookmark for $sura:$ayah" }
        withContext(Dispatchers.IO) {
            database.transaction {
                val bookmark = bookmarkQueries.value
                    .getBookmarkForAyah(sura.toLong(), ayah.toLong())
                    .executeAsOneOrNull()
                if (bookmark != null) {
                    deleteSavedBookmarkByLocalIdInTransaction(bookmark.local_id, null)
                }
            }
        }
        return true
    }

    override suspend fun deleteBookmark(bookmark: AyahBookmark): Boolean {
        logger.i { "Deleting ayah bookmark for ${bookmark.sura}:${bookmark.ayah}" }
        return deleteBookmarkWithLocalId(bookmark.localId)
    }

    override suspend fun deleteBookmark(localId: String): Boolean {
        logger.i { "Deleting ayah bookmark localId=$localId" }
        return deleteBookmarkWithLocalId(localId)
    }

    private suspend fun deleteBookmarkWithLocalId(localId: String): Boolean {
        withContext(Dispatchers.IO) {
            database.transaction {
                deleteSavedBookmarkByLocalIdInTransaction(localId.toLong(), null)
            }
        }
        return true
    }

    private fun deleteSavedBookmarkByLocalIdInTransaction(localId: Long, timestampMillis: Long?) {
        bookmarkQueries.value.clearDefaultCollection(
            local_id = localId,
            timestamp = timestampMillis
        )
        bookmarkCollectionQueries.value.markBookmarkCollectionsDeletedForBookmark(
            bookmark_local_id = localId,
            timestamp = timestampMillis
        )
        reconciler.reconcile()
    }

    override suspend fun fetchMutatedBookmarks(): List<LocalModelMutation<RemoteBookmark>> {
        return withContext(Dispatchers.IO) {
            bookmarkQueries.value.getUnsyncedBookmarkRows()
                .executeAsList()
                .mapNotNull { row -> row.toRemoteBookmarkMutation() }
        }
    }

    override suspend fun markMutatedBookmarksInFlight(acks: List<LocalMutationAck>): List<LocalMutationAck> {
        if (acks.isEmpty()) {
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            val markedAcks = mutableListOf<LocalMutationAck>()
            database.transaction {
                acks.forEach { ack ->
                    if (ack.resource == LocalMutationResource.BOOKMARK &&
                        ack.facet == LOCAL_MUTATION_BOOKMARK_READING_FACET &&
                        ack.observedPendingOp == Mutation.CREATED
                    ) {
                        val localId = ack.localID.toLongOrNull() ?: return@forEach
                        bookmarkQueries.value.markReadingCreateInFlight(
                            local_id = localId,
                            pending_version = ack.observedPendingVersion
                        )
                        val changedRows = bookmarkQueries.value.changedRowCount().executeAsOne()
                        val row = bookmarkQueries.value.getBookmarkByLocalId(localId).executeAsOneOrNull()
                        if (changedRows > 0 &&
                            row?.deleted == 0L &&
                            row.reading_pending_op == "CREATED" &&
                            row.reading_pending_version == ack.observedPendingVersion + 1
                        ) {
                            markedAcks += ack
                        }
                    }
                }
            }
            markedAcks
        }
    }

    override suspend fun rollbackMutatedBookmarksInFlight(acks: List<LocalMutationAck>) {
        if (acks.isEmpty()) {
            return
        }
        withContext(Dispatchers.IO) {
            database.transaction {
                acks.forEach { ack ->
                    if (ack.resource != LocalMutationResource.BOOKMARK ||
                        ack.facet != LOCAL_MUTATION_BOOKMARK_READING_FACET ||
                        ack.observedPendingOp != Mutation.CREATED
                    ) {
                        return@forEach
                    }
                    val localId = ack.localID.toLongOrNull() ?: return@forEach
                    bookmarkQueries.value.rollbackActiveReadingCreateInFlight(
                        local_id = localId,
                        pending_version = ack.observedPendingVersion,
                        marked_pending_version = ack.observedPendingVersion + 1
                    )
                    bookmarkQueries.value.clearCanceledReadingCreateInFlight(
                        local_id = localId,
                        canceled_pending_version = ack.observedPendingVersion + 2
                    )
                    val row = bookmarkQueries.value.getBookmarkByLocalId(localId).executeAsOneOrNull()
                    if (row?.remote_id == null &&
                        row?.deleted == 1L &&
                        row.bookmark_pending_op == "DELETED" &&
                        row.reading_pending_op == null &&
                        row.reading_pending_version == ack.observedPendingVersion + 2
                    ) {
                        bookmarkQueries.value.hardDeleteBookmarkByLocalId(localId)
                    }
                    reconciler.pruneBookmarkIfOrphan(localId)
                }
                reconciler.reconcile()
            }
        }
    }

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteBookmark>>,
        localMutationsToClear: List<LocalModelMutation<RemoteBookmark>>,
        writeBoundaryGuard: PersistenceWriteBoundaryGuard
    ) {
        logger.i {
            "Applying remote bookmark changes with ${updatesToPersist.size} updates to persist and " +
                "${localMutationsToClear.size} local mutations to clear"
        }
        return withContext(Dispatchers.IO) {
            writeBoundaryGuard.checkWriteBoundary()
            database.transaction {
                updatesToPersist.forEach { remote ->
                    when (remote.mutation) {
                        Mutation.CREATED, Mutation.MODIFIED -> {
                            attachRemoteIdForCreatedAck(remote)
                            if (!remote.isPushedBookmarkAck()) {
                                applyRemoteBookmarkUpsert(remote)
                            }
                        }
                        Mutation.DELETED -> {
                            if (!remote.isPushedBookmarkAck()) {
                                applyRemoteBookmarkDeletion(remote)
                            }
                        }
                    }
                }

                localMutationsToClear.forEach { local ->
                    clearLocalBookmarkMutation(local)
                }
                reconciler.reconcile()
            }
        }
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> {
        return buildRemoteResourceExistenceMap(remoteIDs) { chunk ->
            bookmarkQueries.value.checkRemoteIDsExistence(chunk)
                .executeAsList()
                .mapNotNull { it.remote_id }
        }
    }

    override suspend fun fetchBookmarkByRemoteId(remoteId: String): RemoteBookmark? {
        return withContext(Dispatchers.IO) {
            bookmarkQueries.value.getBookmarkByRemoteId(remoteId)
                .executeAsOneOrNull()
                ?.toRemoteBookmark()
        }
    }

    private fun clearLocalBookmarkMutation(local: LocalModelMutation<RemoteBookmark>) {
        val localId = local.localID.toLongOrNull() ?: return
        val ack = local.ack ?: return
        if (ack.localID != local.localID || ack.resource != LocalMutationResource.BOOKMARK) {
            return
        }
        val remoteTimestamp = local.model.lastUpdated.fromPlatform().toEpochMilliseconds()
        val remoteIdToBackfill = remoteIdBackfillFor(localId, local.remoteID)
        when (ack.facet) {
            LOCAL_MUTATION_BOOKMARK_ENTITY_FACET -> {
                val current = bookmarkQueries.value.getBookmarkByLocalId(localId).executeAsOneOrNull() ?: return
                if (ack.observedPendingOp == Mutation.DELETED &&
                    current.deleted == 1L &&
                    current.bookmark_pending_op == ack.observedPendingOp.name &&
                    current.bookmark_pending_version == ack.observedPendingVersion &&
                    current.remote_id == local.remoteID
                ) {
                    bookmarkQueries.value.clearBookmarkDeletePending(
                        local_id = localId,
                        remote_id = remoteIdToBackfill,
                        modified_at = remoteTimestamp,
                        pending_op = ack.observedPendingOp.name,
                        pending_version = ack.observedPendingVersion
                    )
                    reconciler.pruneBookmarkIfOrphan(localId)
                    return
                }
                bookmarkQueries.value.clearBookmarkPending(
                    local_id = localId,
                    remote_id = remoteIdToBackfill,
                    modified_at = remoteTimestamp,
                    clear_reading = 0L,
                    pending_op = ack.observedPendingOp.name,
                    pending_version = ack.observedPendingVersion
                )
            }
            LOCAL_MUTATION_BOOKMARK_READING_FACET -> {
                bookmarkQueries.value.clearReadingPending(
                    local_id = localId,
                    remote_id = remoteIdToBackfill,
                    modified_at = remoteTimestamp,
                    pending_op = ack.observedPendingOp.name,
                    pending_version = ack.observedPendingVersion
                )
            }
            else -> {
                return
            }
        }
    }

    private fun RemoteModelMutation<RemoteBookmark>.isPushedBookmarkAck(): Boolean {
        return ack?.resource == LocalMutationResource.BOOKMARK
    }

    private fun attachRemoteIdForCreatedAck(remote: RemoteModelMutation<RemoteBookmark>) {
        val ack = remote.ack ?: return
        if (ack.resource != LocalMutationResource.BOOKMARK ||
            ack.observedPendingOp != Mutation.CREATED
        ) {
            return
        }
        val localId = ack.localID.toLongOrNull() ?: return
        val row = bookmarkQueries.value.getBookmarkByLocalId(localId).executeAsOneOrNull() ?: return
        if (!row.matches(remote.model)) {
            return
        }
        val owner = bookmarkQueries.value.getBookmarkByRemoteId(remote.remoteID).executeAsOneOrNull()
        if (owner != null && owner.local_id != localId) {
            return
        }
        bookmarkQueries.value.attachRemoteBookmarkIdByLocalId(
            local_id = localId,
            remote_id = remote.remoteID,
            modified_at = remote.model.lastUpdated.fromPlatform().toEpochMilliseconds()
        )
    }

    private fun remoteIdBackfillFor(localId: Long, remoteId: String?): String? {
        remoteId ?: return null
        val owner = bookmarkQueries.value.getBookmarkByRemoteId(remoteId).executeAsOneOrNull()
        return if (owner == null || owner.local_id == localId) {
            remoteId
        } else {
            null
        }
    }

    private fun applyRemoteBookmarkUpsert(remote: RemoteModelMutation<RemoteBookmark>) {
        val updatedAt = remote.model.lastUpdated.fromPlatform().toEpochMilliseconds()
        // Backend bookmark IDs are stable canonical row IDs and are not reused or moved between locations.
        val existingByRemoteId = bookmarkQueries.value.getBookmarkByRemoteId(remote.remoteID).executeAsOneOrNull()
        if (existingByRemoteId != null && !existingByRemoteId.matches(remote.model)) {
            logger.w {
                "Skipping remote bookmark ${remote.remoteID}; existing row is a different location."
            }
            return
        }
        val existingAtLocation = getBookmarkAtLocation(remote.model)
        if (existingAtLocation?.remote_id != null &&
            existingAtLocation.remote_id != remote.remoteID
        ) {
            logger.w {
                "Skipping remote bookmark ${remote.remoteID}; location already belongs to ${existingAtLocation.remote_id}."
            }
            return
        }
        val row = when (val model = remote.model) {
            is RemoteBookmark.Ayah -> {
                bookmarkQueries.value.upsertAyahBookmark(
                    remote_id = remote.remoteID,
                    ayah_id = getAyahId(model.sura, model.ayah).toLong(),
                    sura = model.sura.toLong(),
                    ayah = model.ayah.toLong(),
                    timestamp = updatedAt
                )
                bookmarkQueries.value.getBookmarkForAyah(model.sura.toLong(), model.ayah.toLong())
                    .executeAsOne()
            }
            is RemoteBookmark.Page -> {
                bookmarkQueries.value.upsertPageBookmark(
                    remote_id = remote.remoteID,
                    page = model.page.toLong(),
                    timestamp = updatedAt
                )
                bookmarkQueries.value.getBookmarkForPage(model.page.toLong())
                    .executeAsOne()
            }
        }

        bookmarkQueries.value.applyRemoteReading(
            local_id = row.local_id,
            remote_id = remote.remoteID,
            is_reading = if (remote.model.isReading) 1L else 0L,
            modified_at = updatedAt
        )
    }

    private fun getBookmarkAtLocation(bookmark: RemoteBookmark): DatabaseBookmark? {
        return when (bookmark) {
            is RemoteBookmark.Ayah -> bookmarkQueries.value
                .getBookmarkForAyah(bookmark.sura.toLong(), bookmark.ayah.toLong())
                .executeAsOneOrNull()
            is RemoteBookmark.Page -> bookmarkQueries.value
                .getBookmarkForPage(bookmark.page.toLong())
                .executeAsOneOrNull()
        }
    }

    private fun DatabaseBookmark.matches(bookmark: RemoteBookmark): Boolean {
        return when (bookmark) {
            is RemoteBookmark.Ayah ->
                bookmark_type == "AYAH" &&
                    sura == bookmark.sura.toLong() &&
                    ayah == bookmark.ayah.toLong()
            is RemoteBookmark.Page ->
                bookmark_type == "PAGE" &&
                    page == bookmark.page.toLong()
        }
    }

    private fun applyRemoteBookmarkDeletion(remote: RemoteModelMutation<RemoteBookmark>) {
        val row = bookmarkQueries.value.getBookmarkByRemoteId(remote.remoteID).executeAsOneOrNull()
        if (row == null) {
            bookmarkQueries.value.hardDeleteBookmarkByRemoteId(remote_id = remote.remoteID)
            return
        }

        val updatedAt = remote.model.lastUpdated.fromPlatform().toEpochMilliseconds()
        bookmarkCollectionQueries.value.markBookmarkCollectionsDeletedForBookmark(
            bookmark_local_id = row.local_id,
            timestamp = updatedAt
        )
        bookmarkQueries.value.applyRemoteBookmarkDeletedByLocalId(
            local_id = row.local_id,
            modified_at = updatedAt
        )
        reconciler.pruneBookmarkIfOrphan(row.local_id)
    }

    private fun DatabaseBookmark.toRemoteBookmarkMutation(): LocalModelMutation<RemoteBookmark>? {
        val model = toRemoteBookmark()
        val pendingOp = when {
            deleted == 1L || bookmark_pending_op == "DELETED" -> Mutation.DELETED
            reading_pending_op != null -> reading_pending_op.toMutationOrNull() ?: Mutation.CREATED
            bookmark_pending_op == "MODIFIED" -> Mutation.MODIFIED
            bookmark_pending_op == "CREATED" -> Mutation.CREATED
            else -> return null
        }
        val facet = when {
            deleted == 1L || bookmark_pending_op != null -> LOCAL_MUTATION_BOOKMARK_ENTITY_FACET
            reading_pending_op != null -> LOCAL_MUTATION_BOOKMARK_READING_FACET
            else -> return null
        }
        val version = when (facet) {
            LOCAL_MUTATION_BOOKMARK_ENTITY_FACET -> bookmark_pending_version
            LOCAL_MUTATION_BOOKMARK_READING_FACET -> reading_pending_version
            else -> return null
        }
        return LocalModelMutation(
            mutation = pendingOp,
            model = model,
            remoteID = remote_id,
            localID = local_id.toString(),
            ack = LocalMutationAck(
                localID = local_id.toString(),
                resource = LocalMutationResource.BOOKMARK,
                facet = facet,
                observedPendingOp = pendingOp,
                observedPendingVersion = version
            )
        )
    }

    private fun String?.toMutationOrNull(): Mutation? {
        return when (this) {
            "CREATED" -> Mutation.CREATED
            "MODIFIED" -> Mutation.MODIFIED
            "DELETED" -> Mutation.DELETED
            else -> null
        }
    }

    private fun DatabaseBookmark.toRemoteBookmark(): RemoteBookmark {
        val updatedAt = Instant.fromEpochMilliseconds(
            if (deleted == 1L || bookmark_pending_op == "DELETED") {
                modified_at
            } else {
                reading_modified_at ?: bookmark_modified_at
            }
        ).toPlatform()
        return when (bookmark_type) {
            "AYAH" -> RemoteBookmark.Ayah(
                sura = requireNotNull(sura).toInt(),
                ayah = requireNotNull(ayah).toInt(),
                isReading = is_reading == 1L,
                lastUpdated = updatedAt
            )
            "PAGE" -> RemoteBookmark.Page(
                page = requireNotNull(page).toInt(),
                isReading = is_reading == 1L,
                lastUpdated = updatedAt
            )
            else -> error("Unsupported bookmark type: $bookmark_type")
        }
    }

    private fun normalizeCollectionIds(collectionLocalIds: List<String>?): List<String> {
        val nonBlankIds = collectionLocalIds
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
        return nonBlankIds.ifEmpty { listOf(DEFAULT_COLLECTION_ID) }
    }

    private fun getAyahId(sura: Int, ayah: Int): Int {
        return QuranData.getAyahId(sura, ayah)
    }
}
