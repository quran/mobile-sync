package com.quran.shared.persistence.repository.collectionbookmark.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.touchlab.kermit.Logger
import com.quran.shared.di.AppScope
import com.quran.shared.mutations.LOCAL_MUTATION_BOOKMARK_DEFAULT_FACET
import com.quran.shared.mutations.LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.LocalMutationAck
import com.quran.shared.mutations.LocalMutationResource
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.input.LocalSyncCollectionAyahBookmark
import com.quran.shared.persistence.input.RemoteCollectionBookmark
import com.quran.shared.persistence.model.AyahBookmark
import com.quran.shared.persistence.model.CollectionAyahBookmark
import com.quran.shared.persistence.model.DEFAULT_COLLECTION_ID
import com.quran.shared.persistence.model.DatabaseBookmark
import com.quran.shared.persistence.model.DatabaseBookmarkCollection
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
class CollectionBookmarksRepositoryImpl(
    private val database: QuranDatabase,
    private val reconciler: BookmarkDependencyReconciler = BookmarkDependencyReconciler(database)
) : CollectionBookmarksRepository, CollectionBookmarksSynchronizationRepository {

    private val logger = Logger.withTag("CollectionBookmarksRepository")
    private val bookmarkCollectionQueries = lazy { database.bookmark_collectionsQueries }
    private val bookmarkQueries = lazy { database.bookmarksQueries }
    private val collectionQueries = lazy { database.collectionsQueries }

    override suspend fun getBookmarksForCollection(collectionLocalId: String): List<CollectionAyahBookmark> {
        if (collectionLocalId == DEFAULT_COLLECTION_ID) {
            return withContext(Dispatchers.IO) {
                bookmarkQueries.value.getSavedAyahBookmarks()
                    .executeAsList()
                    .filter { it.is_in_default_collection == 1L }
                    .map { it.toDefaultCollectionBookmark() }
            }
        }
        return withContext(Dispatchers.IO) {
            bookmarkCollectionQueries.value
                .getCollectionBookmarksForCollectionWithDetails(collection_local_id = collectionLocalId.toLong())
                .executeAsList()
                .mapNotNull { record ->
                    toCollectionBookmark(
                        bookmarkLocalId = record.bookmark_local_id,
                        bookmarkRemoteId = record.bookmark_remote_id,
                        sura = record.sura,
                        ayah = record.ayah,
                        collectionLocalId = record.collection_local_id,
                        collectionRemoteId = record.collection_remote_id,
                        modifiedAt = record.modified_at,
                        localId = record.local_id,
                        logMissingBookmark = false
                    )
                }
        }
    }

    override fun getBookmarksForCollectionFlow(collectionLocalId: String): Flow<List<CollectionAyahBookmark>> {
        return if (collectionLocalId == DEFAULT_COLLECTION_ID) {
            bookmarkQueries.value.getSavedAyahBookmarks()
                .asFlow()
                .mapToList(Dispatchers.IO)
                .map { list ->
                    list.filter { it.is_in_default_collection == 1L }
                        .map { it.toDefaultCollectionBookmark() }
                }
        } else {
            bookmarkCollectionQueries.value
                .getCollectionBookmarksForCollectionWithDetails(collection_local_id = collectionLocalId.toLong())
                .asFlow()
                .mapToList(Dispatchers.IO)
                .map { list ->
                    list.mapNotNull { record ->
                        toCollectionBookmark(
                            bookmarkLocalId = record.bookmark_local_id,
                            bookmarkRemoteId = record.bookmark_remote_id,
                            sura = record.sura,
                            ayah = record.ayah,
                            collectionLocalId = record.collection_local_id,
                            collectionRemoteId = record.collection_remote_id,
                            modifiedAt = record.modified_at,
                            localId = record.local_id,
                            logMissingBookmark = false
                        )
                    }
                }
        }
    }

    override suspend fun addBookmarkToCollection(
        collectionLocalId: String,
        bookmark: AyahBookmark
    ): CollectionAyahBookmark {
        return addBookmarkToCollectionWithTimestampMillis(collectionLocalId, bookmark, timestampMillis = null)
    }

    override suspend fun addBookmarkToCollection(
        collectionLocalId: String,
        bookmark: AyahBookmark,
        timestamp: PlatformDateTime
    ): CollectionAyahBookmark {
        return addBookmarkToCollectionWithTimestampMillis(
            collectionLocalId,
            bookmark,
            timestamp.toEpochMillisecondsOrNull()
        )
    }

    private suspend fun addBookmarkToCollectionWithTimestampMillis(
        collectionLocalId: String,
        bookmark: AyahBookmark,
        timestampMillis: Long?
    ): CollectionAyahBookmark {
        return withContext(Dispatchers.IO) {
            var created: CollectionAyahBookmark? = null
            database.transaction {
                val row = requireNotNull(
                    bookmarkQueries.value.getBookmarkByLocalId(bookmark.localId.toLong()).executeAsOneOrNull()
                ) { "Bookmark not found for localId=${bookmark.localId}." }

                if (collectionLocalId == DEFAULT_COLLECTION_ID) {
                    bookmarkQueries.value.addAyahToDefaultCollection(
                        ayah_id = getAyahId(bookmark.sura, bookmark.ayah).toLong(),
                        sura = bookmark.sura.toLong(),
                        ayah = bookmark.ayah.toLong(),
                        timestamp = timestampMillis
                    )
                    reconciler.reconcile()
                    created = bookmarkQueries.value.getBookmarkByLocalId(row.local_id)
                        .executeAsOne()
                        .toDefaultCollectionBookmark()
                    return@transaction
                }

                val collection = collectionQueries.value
                    .getCollectionByLocalId(collectionLocalId.toLong())
                    .executeAsOneOrNull()
                requireNotNull(collection) { "Collection not found for localId=$collectionLocalId." }

                bookmarkCollectionQueries.value.addBookmarkToCollection(
                    bookmark_local_id = row.local_id,
                    collection_local_id = collection.local_id,
                    timestamp = timestampMillis
                )
                reconciler.reconcile()
                val record = bookmarkCollectionQueries.value
                    .getCollectionBookmarksForCollectionWithDetails(collection.local_id)
                    .executeAsList()
                    .first { it.bookmark_local_id == row.local_id }
                created = toCollectionBookmark(
                    bookmarkLocalId = record.bookmark_local_id,
                    bookmarkRemoteId = record.bookmark_remote_id,
                    sura = record.sura,
                    ayah = record.ayah,
                    collectionLocalId = record.collection_local_id,
                    collectionRemoteId = record.collection_remote_id,
                    modifiedAt = record.modified_at,
                    localId = record.local_id,
                    logMissingBookmark = true
                )
            }
            requireNotNull(created)
        }
    }

    override suspend fun addAyahBookmarkToCollection(
        collectionLocalId: String,
        sura: Int,
        ayah: Int
    ): CollectionAyahBookmark {
        return addAyahBookmarkToCollectionWithTimestampMillis(collectionLocalId, sura, ayah, timestampMillis = null)
    }

    override suspend fun addAyahBookmarkToCollection(
        collectionLocalId: String,
        sura: Int,
        ayah: Int,
        timestamp: PlatformDateTime
    ): CollectionAyahBookmark {
        return addAyahBookmarkToCollectionWithTimestampMillis(
            collectionLocalId,
            sura,
            ayah,
            timestamp.toEpochMillisecondsOrNull()
        )
    }

    private suspend fun addAyahBookmarkToCollectionWithTimestampMillis(
        collectionLocalId: String,
        sura: Int,
        ayah: Int,
        timestampMillis: Long?
    ): CollectionAyahBookmark {
        return withContext(Dispatchers.IO) {
            var created: CollectionAyahBookmark? = null
            database.transaction {
                val ayahId = getAyahId(sura, ayah).toLong()
                if (collectionLocalId == DEFAULT_COLLECTION_ID) {
                    bookmarkQueries.value.addAyahToDefaultCollection(
                        ayah_id = ayahId,
                        sura = sura.toLong(),
                        ayah = ayah.toLong(),
                        timestamp = timestampMillis
                    )
                    reconciler.reconcile()
                    created = bookmarkQueries.value.getBookmarkForAyah(sura.toLong(), ayah.toLong())
                        .executeAsOne()
                        .toDefaultCollectionBookmark()
                    return@transaction
                }

                bookmarkQueries.value.upsertAyahBookmark(
                    remote_id = null,
                    ayah_id = ayahId,
                    sura = sura.toLong(),
                    ayah = ayah.toLong(),
                    timestamp = timestampMillis
                )
                val bookmark = requireNotNull(
                    bookmarkQueries.value.getBookmarkForAyah(sura.toLong(), ayah.toLong()).executeAsOneOrNull()
                ) { "Expected ayah bookmark for $sura:$ayah after insert." }
                val collection = collectionQueries.value
                    .getCollectionByLocalId(collectionLocalId.toLong())
                    .executeAsOneOrNull()
                requireNotNull(collection) { "Collection not found for localId=$collectionLocalId." }

                bookmarkCollectionQueries.value.addBookmarkToCollection(
                    bookmark_local_id = bookmark.local_id,
                    collection_local_id = collection.local_id,
                    timestamp = timestampMillis
                )
                reconciler.reconcile()
                val record = bookmarkCollectionQueries.value
                    .getCollectionBookmarksForCollectionWithDetails(collection.local_id)
                    .executeAsList()
                    .first { it.bookmark_local_id == bookmark.local_id }
                created = toCollectionBookmark(
                    bookmarkLocalId = record.bookmark_local_id,
                    bookmarkRemoteId = record.bookmark_remote_id,
                    sura = record.sura,
                    ayah = record.ayah,
                    collectionLocalId = record.collection_local_id,
                    collectionRemoteId = record.collection_remote_id,
                    modifiedAt = record.modified_at,
                    localId = record.local_id,
                    logMissingBookmark = true
                )
            }
            requireNotNull(created)
        }
    }

    override suspend fun removeBookmarkFromCollection(
        collectionLocalId: String,
        bookmark: AyahBookmark
    ): Boolean {
        return withContext(Dispatchers.IO) {
            database.transaction {
                if (collectionLocalId == DEFAULT_COLLECTION_ID) {
                    bookmarkQueries.value.clearDefaultCollection(
                        local_id = bookmark.localId.toLong(),
                        timestamp = null
                    )
                } else {
                    bookmarkCollectionQueries.value.markBookmarkCollectionDeleted(
                        bookmark_local_id = bookmark.localId.toLong(),
                        collection_local_id = collectionLocalId.toLong(),
                        timestamp = null
                    )
                }
                reconciler.reconcile()
            }
            true
        }
    }

    override suspend fun removeAyahBookmarkFromCollection(collectionAyahBookmark: CollectionAyahBookmark): Boolean {
        return withContext(Dispatchers.IO) {
            database.transaction {
                if (collectionAyahBookmark.collectionLocalId == DEFAULT_COLLECTION_ID) {
                    bookmarkQueries.value.clearDefaultCollection(
                        local_id = collectionAyahBookmark.bookmarkLocalId.toLong(),
                        timestamp = null
                    )
                } else {
                    bookmarkCollectionQueries.value.markBookmarkCollectionDeleted(
                        bookmark_local_id = collectionAyahBookmark.bookmarkLocalId.toLong(),
                        collection_local_id = collectionAyahBookmark.collectionLocalId.toLong(),
                        timestamp = null
                    )
                }
                reconciler.reconcile()
            }
            true
        }
    }

    override suspend fun fetchMutatedCollectionBookmarks(): List<LocalModelMutation<LocalSyncCollectionAyahBookmark>> {
        return withContext(Dispatchers.IO) {
            val defaultMutations = bookmarkQueries.value.getDefaultPendingBookmarks()
                .executeAsList()
                .map { row ->
                    val mutation = if (row.default_pending_op == "DELETED") Mutation.DELETED else Mutation.CREATED
                    val relationBookmarkRemoteId = if (mutation == Mutation.DELETED) {
                        row.default_last_synced_bookmark_remote_id ?: row.remote_id
                    } else {
                        row.remote_id
                    }
                    LocalModelMutation(
                        mutation = mutation,
                        model = defaultLocalSyncCollectionBookmark(
                            bookmarkLocalId = row.local_id,
                            bookmarkRemoteId = relationBookmarkRemoteId,
                            sura = row.sura,
                            ayah = row.ayah,
                            modifiedAt = row.default_modified_at ?: row.modified_at,
                            createdAt = row.created_at
                        ),
                        remoteID = relationBookmarkRemoteId?.let {
                            collectionBookmarkRemoteId(DEFAULT_COLLECTION_ID, it)
                        },
                        localID = defaultLocalId(row.local_id),
                        ack = LocalMutationAck(
                            localID = defaultLocalId(row.local_id),
                            resource = LocalMutationResource.COLLECTION_BOOKMARK,
                            facet = LOCAL_MUTATION_BOOKMARK_DEFAULT_FACET,
                            observedPendingOp = mutation,
                            observedPendingVersion = row.default_pending_version
                        )
                    )
                }
            val customMutations = bookmarkCollectionQueries.value.getUnsyncedCollectionBookmarksWithDetails()
                .executeAsList()
                .mapNotNull { record ->
                    val mutation = when (record.pending_op) {
                        "DELETED" -> Mutation.DELETED
                        "CREATED" -> Mutation.CREATED
                        else -> return@mapNotNull null
                    }
                    val collectionRemoteId = if (mutation == Mutation.DELETED) {
                        record.last_synced_collection_remote_id
                    } else {
                        record.collection_remote_id
                    }
                    val bookmarkRemoteId = if (mutation == Mutation.DELETED) {
                        record.last_synced_bookmark_remote_id
                    } else {
                        record.bookmark_remote_id
                    }
                    val collectionBookmark = toLocalSyncCollectionBookmark(
                        bookmarkLocalId = record.bookmark_local_id,
                        bookmarkRemoteId = bookmarkRemoteId,
                        sura = record.sura,
                        ayah = record.ayah,
                        collectionLocalId = record.collection_local_id,
                        collectionRemoteId = collectionRemoteId,
                        modifiedAt = record.modified_at,
                        createdAt = record.created_at,
                        localId = record.local_id,
                        logMissingBookmark = true
                    ) ?: return@mapNotNull null
                    LocalModelMutation(
                        mutation = mutation,
                        model = collectionBookmark,
                        remoteID = if (!collectionRemoteId.isNullOrEmpty() && !bookmarkRemoteId.isNullOrEmpty()) {
                            collectionBookmarkRemoteId(collectionRemoteId, bookmarkRemoteId)
                        } else {
                            null
                        },
                        localID = record.local_id.toString(),
                        ack = LocalMutationAck(
                            localID = record.local_id.toString(),
                            resource = LocalMutationResource.COLLECTION_BOOKMARK,
                            facet = LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET,
                            observedPendingOp = mutation,
                            observedPendingVersion = record.pending_version
                        )
                    )
                }
            defaultMutations + customMutations
        }
    }

    override suspend fun markMutatedCollectionBookmarksInFlight(acks: List<LocalMutationAck>): List<LocalMutationAck> {
        if (acks.isEmpty()) {
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            val markedAcks = mutableListOf<LocalMutationAck>()
            database.transaction {
                acks.forEach { ack ->
                    if (ack.resource != LocalMutationResource.COLLECTION_BOOKMARK ||
                        ack.observedPendingOp != Mutation.CREATED
                    ) {
                        return@forEach
                    }
                    when (ack.facet) {
                        LOCAL_MUTATION_BOOKMARK_DEFAULT_FACET -> {
                            val bookmarkLocalId = ack.localID
                                .removePrefix(DEFAULT_LOCAL_ID_PREFIX)
                                .toLongOrNull() ?: return@forEach
                            bookmarkQueries.value.markDefaultCreateInFlight(
                                local_id = bookmarkLocalId,
                                pending_version = ack.observedPendingVersion
                            )
                            val changedRows = bookmarkQueries.value.changedRowCount().executeAsOne()
                            val row = bookmarkQueries.value.getBookmarkByLocalId(bookmarkLocalId).executeAsOneOrNull()
                            if (changedRows > 0 &&
                                row?.is_in_default_collection == 1L &&
                                row.default_pending_op == "CREATED" &&
                                row.default_pending_version == ack.observedPendingVersion + 1
                            ) {
                                markedAcks += ack
                            }
                        }
                        LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET -> {
                            val localId = ack.localID.toLongOrNull() ?: return@forEach
                            bookmarkCollectionQueries.value.markCreatedMutationInFlight(
                                id = localId,
                                pending_version = ack.observedPendingVersion
                            )
                            val changedRows = bookmarkCollectionQueries.value.changedRowCount().executeAsOne()
                            val row = bookmarkCollectionQueries.value
                                .getCollectionBookmarkByLocalId(localId)
                                .executeAsOneOrNull()
                            if (changedRows > 0 &&
                                row?.is_active == 1L &&
                                row.pending_op == "CREATED" &&
                                row.pending_version == ack.observedPendingVersion + 1
                            ) {
                                markedAcks += ack
                            }
                        }
                    }
                }
            }
            markedAcks
        }
    }

    override suspend fun rollbackMutatedCollectionBookmarksInFlight(acks: List<LocalMutationAck>) {
        if (acks.isEmpty()) {
            return
        }
        withContext(Dispatchers.IO) {
            database.transaction {
                acks.forEach { ack ->
                    if (ack.resource != LocalMutationResource.COLLECTION_BOOKMARK ||
                        ack.observedPendingOp != Mutation.CREATED
                    ) {
                        return@forEach
                    }
                    when (ack.facet) {
                        LOCAL_MUTATION_BOOKMARK_DEFAULT_FACET -> {
                            val bookmarkLocalId = ack.localID
                                .removePrefix(DEFAULT_LOCAL_ID_PREFIX)
                                .toLongOrNull() ?: return@forEach
                            bookmarkQueries.value.rollbackActiveDefaultCreateInFlight(
                                local_id = bookmarkLocalId,
                                pending_version = ack.observedPendingVersion,
                                marked_pending_version = ack.observedPendingVersion + 1
                            )
                            bookmarkQueries.value.clearCanceledDefaultCreateInFlight(
                                local_id = bookmarkLocalId,
                                canceled_pending_version = ack.observedPendingVersion + 2
                            )
                            reconciler.pruneBookmarkIfOrphan(bookmarkLocalId)
                        }
                        LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET -> {
                            val localId = ack.localID.toLongOrNull() ?: return@forEach
                            val bookmarkLocalId = bookmarkCollectionQueries.value
                                .getCollectionBookmarkByLocalId(localId)
                                .executeAsOneOrNull()
                                ?.bookmark_local_id
                            bookmarkCollectionQueries.value.rollbackActiveCreatedMutationInFlight(
                                id = localId,
                                pending_version = ack.observedPendingVersion,
                                marked_pending_version = ack.observedPendingVersion + 1
                            )
                            bookmarkCollectionQueries.value.deleteCanceledCreatedMutationInFlight(
                                id = localId,
                                canceled_pending_version = ack.observedPendingVersion + 2
                            )
                            bookmarkLocalId?.let(reconciler::pruneBookmarkIfOrphan)
                        }
                    }
                }
                reconciler.reconcile()
            }
        }
    }

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteCollectionBookmark>>,
        localMutationsToClear: List<LocalModelMutation<LocalSyncCollectionAyahBookmark>>,
        writeBoundaryGuard: PersistenceWriteBoundaryGuard
    ) {
        logger.i {
            "Applying remote collection bookmark changes with " +
                "${updatesToPersist.size} updates to persist and ${localMutationsToClear.size} local mutations to clear"
        }
        return withContext(Dispatchers.IO) {
            writeBoundaryGuard.checkWriteBoundary()
            database.transaction {
                localMutationsToClear.forEach { local ->
                    clearLocalMutation(local)
                }

                updatesToPersist.forEach { remote ->
                    when (remote.mutation) {
                        Mutation.CREATED -> applyRemoteCollectionBookmarkUpsert(remote)
                        Mutation.DELETED -> applyRemoteCollectionBookmarkDeletion(remote)
                        Mutation.MODIFIED ->
                            throw RuntimeException("Unexpected MODIFIED remote modification for collection bookmarks.")
                    }
                }
                reconciler.reconcile()
            }
        }
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> {
        return buildRemoteResourceExistenceMap(remoteIDs) { chunk ->
            bookmarkCollectionQueries.value
                .checkRemoteIDsExistence(chunk)
                .executeAsList()
                .mapNotNull { it.remote_id } + chunk.mapNotNull(::defaultCollectionRemoteIdIfExists)
        }
    }

    private fun defaultCollectionRemoteIdIfExists(remoteId: String): String? {
        if (!remoteId.startsWith("$DEFAULT_COLLECTION_ID-")) {
            return null
        }
        val bookmarkRemoteId = remoteId.removePrefix("$DEFAULT_COLLECTION_ID-")
        val bookmark = findBookmarkByDefaultRelationBookmarkRemoteId(bookmarkRemoteId)
        return if (bookmark != null &&
            bookmark.deleted == 0L &&
            (bookmark.is_in_default_collection == 1L || bookmark.default_pending_op == "DELETED")
        ) {
            remoteId
        } else {
            null
        }
    }

    override suspend fun fetchCollectionBookmarkByRemoteId(remoteId: String): CollectionAyahBookmark? {
        return withContext(Dispatchers.IO) {
            if (remoteId.startsWith("$DEFAULT_COLLECTION_ID-")) {
                val bookmarkRemoteId = remoteId.removePrefix("$DEFAULT_COLLECTION_ID-")
                val row = findBookmarkByDefaultRelationBookmarkRemoteId(bookmarkRemoteId)
                if (row != null &&
                    row.deleted == 0L &&
                    (row.is_in_default_collection == 1L || row.default_pending_op == "DELETED")
                ) {
                    return@withContext row.toDefaultCollectionBookmark(bookmarkRemoteId)
                }
                return@withContext null
            }

            bookmarkCollectionQueries.value.getCollectionBookmarkWithDetailsByRemoteId(remote_id = remoteId)
                .executeAsOneOrNull()
                ?.let { record ->
                    val matchedSnapshot = record.last_synced_collection_remote_id != null &&
                        record.last_synced_bookmark_remote_id != null &&
                        collectionBookmarkRemoteId(
                            record.last_synced_collection_remote_id,
                            record.last_synced_bookmark_remote_id
                        ) == remoteId
                    toCollectionBookmark(
                        bookmarkLocalId = record.bookmark_local_id,
                        bookmarkRemoteId = if (matchedSnapshot) {
                            record.last_synced_bookmark_remote_id
                        } else {
                            record.bookmark_remote_id ?: record.last_synced_bookmark_remote_id
                        },
                        sura = record.sura,
                        ayah = record.ayah,
                        collectionLocalId = record.collection_local_id,
                        collectionRemoteId = if (matchedSnapshot) {
                            record.last_synced_collection_remote_id
                        } else {
                            record.collection_remote_id ?: record.last_synced_collection_remote_id
                        },
                        modifiedAt = record.modified_at,
                        localId = record.local_id,
                        logMissingBookmark = false
                    )
                }
        }
    }

    private fun clearLocalMutation(local: LocalModelMutation<LocalSyncCollectionAyahBookmark>) {
        val updatedAt = local.model.lastUpdated.fromPlatform().toEpochMilliseconds()
        if (local.localID.startsWith(DEFAULT_LOCAL_ID_PREFIX)) {
            val bookmarkLocalId = local.localID.removePrefix(DEFAULT_LOCAL_ID_PREFIX).toLongOrNull() ?: return
            clearDefaultLocalMutation(local, bookmarkLocalId, updatedAt)
            return
        }

        val localId = local.localID.toLongOrNull() ?: return
        val relationRow = bookmarkCollectionQueries.value
            .getCollectionBookmarkByLocalId(localId)
            .executeAsOneOrNull()
        if (local.mutation == Mutation.DELETED) {
            clearCustomDeleteMutation(local, relationRow, updatedAt)
            return
        }
        if (relationRow?.pending_op == "DELETED" && relationRow.is_active == 0L) {
            bindCustomCreatedAckToPendingDelete(local, relationRow, updatedAt)
            return
        }
        if (relationRow?.pending_op != "CREATED" || relationRow.is_active != 1L) {
            return
        }
        if (!ackMatches(local, LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET)) {
            return
        }
        val ack = local.ack ?: return
        val relationBookmarkRemoteId = local.relationBookmarkRemoteId()
        if (local.mutation == Mutation.CREATED && relationBookmarkRemoteId.isNullOrEmpty()) {
            return
        }
        bookmarkCollectionQueries.value.clearLocalMutationFor(
            id = localId,
            bookmark_remote_id = relationBookmarkRemoteId,
            collection_remote_id = local.model.collectionRemoteId,
            modified_at = updatedAt,
            pending_op = ack.observedPendingOp.name,
            pending_version = ack.observedPendingVersion
        )
        if (!local.model.bookmarkRemoteId.isNullOrEmpty() && local.mutation != Mutation.DELETED) {
            val bookmarkLocalId = relationRow.bookmark_local_id
            bookmarkQueries.value.getBookmarkByLocalId(bookmarkLocalId)
                .executeAsOneOrNull()
                ?.takeIf { it.remote_id == null }
                ?.let {
                    upsertRelationBookmarkRemoteId(local.model, bookmarkLocalId, updatedAt)
                }
        }
    }

    private fun clearDefaultLocalMutation(
        local: LocalModelMutation<LocalSyncCollectionAyahBookmark>,
        bookmarkLocalId: Long,
        updatedAt: Long
    ) {
        val row = bookmarkQueries.value.getBookmarkByLocalId(bookmarkLocalId).executeAsOneOrNull() ?: return
        if (!ackMatches(local, LOCAL_MUTATION_BOOKMARK_DEFAULT_FACET)) {
            return
        }
        val ack = local.ack ?: return
        if (local.mutation == Mutation.DELETED) {
            if (row.default_pending_op == "DELETED" &&
                row.is_in_default_collection == 0L &&
                row.default_pending_version == ack.observedPendingVersion
            ) {
                bookmarkQueries.value.clearDefaultPending(
                    local_id = bookmarkLocalId,
                    remote_id = null,
                    default_last_synced_bookmark_remote_id = null,
                    modified_at = updatedAt,
                    pending_op = ack.observedPendingOp.name,
                    pending_version = ack.observedPendingVersion
                )
                if (row.remote_id == local.model.bookmarkRemoteId ||
                    row.default_last_synced_bookmark_remote_id == local.model.bookmarkRemoteId
                ) {
                    reconciler.pruneBookmarkIfOrphan(bookmarkLocalId)
                }
            } else if (row.default_pending_op == null &&
                row.is_in_default_collection == 1L &&
                (
                    row.remote_id == local.model.bookmarkRemoteId ||
                        row.default_last_synced_bookmark_remote_id == local.model.bookmarkRemoteId
                )
            ) {
                bookmarkQueries.value.markDefaultRelationForRecreation(
                    local_id = bookmarkLocalId,
                    modified_at = updatedAt
                )
            }
            return
        }

        if (row.default_pending_op == "DELETED" &&
            row.is_in_default_collection == 0L &&
            ack.observedPendingOp == Mutation.CREATED
        ) {
            val relationBookmarkRemoteId = local.relationBookmarkRemoteId() ?: return
            bookmarkQueries.value.bindDefaultRemoteSnapshotForCreatedAck(
                local_id = bookmarkLocalId,
                remote_id = local.model.bookmarkRemoteId,
                default_last_synced_bookmark_remote_id = relationBookmarkRemoteId,
                modified_at = updatedAt
            )
            return
        }

        if (row.default_pending_op == "DELETED" &&
            row.is_in_default_collection == 1L &&
            ack.observedPendingOp == Mutation.CREATED
        ) {
            val relationBookmarkRemoteId = local.relationBookmarkRemoteId() ?: return
            bookmarkQueries.value.clearReaddedDefaultCreatedAck(
                local_id = bookmarkLocalId,
                remote_id = local.model.bookmarkRemoteId,
                default_last_synced_bookmark_remote_id = relationBookmarkRemoteId,
                modified_at = updatedAt
            )
            return
        }

        if (row.default_pending_op != "CREATED" || row.is_in_default_collection != 1L) {
            return
        }
        val relationBookmarkRemoteId = local.relationBookmarkRemoteId() ?: return
        bookmarkQueries.value.clearDefaultPending(
            local_id = bookmarkLocalId,
            remote_id = local.model.bookmarkRemoteId,
            default_last_synced_bookmark_remote_id = relationBookmarkRemoteId,
            modified_at = updatedAt,
            pending_op = ack.observedPendingOp.name,
            pending_version = ack.observedPendingVersion
        )
    }

    private fun bindCustomCreatedAckToPendingDelete(
        local: LocalModelMutation<LocalSyncCollectionAyahBookmark>,
        relationRow: DatabaseBookmarkCollection,
        updatedAt: Long
    ) {
        if (!ackMatches(local, LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET)) {
            return
        }
        val ack = local.ack ?: return
        if (ack.observedPendingOp != Mutation.CREATED) {
            return
        }
        val relationBookmarkRemoteId = local.relationBookmarkRemoteId() ?: return
        bookmarkCollectionQueries.value.bindRemoteSnapshotForCreatedAck(
            id = relationRow.local_id,
            bookmark_remote_id = relationBookmarkRemoteId,
            collection_remote_id = local.model.collectionRemoteId,
            modified_at = updatedAt
        )
        if (!local.model.bookmarkRemoteId.isNullOrEmpty()) {
            bookmarkQueries.value.getBookmarkByLocalId(relationRow.bookmark_local_id)
                .executeAsOneOrNull()
                ?.takeIf { it.remote_id == null }
                ?.let {
                    bookmarkQueries.value.attachRemoteBookmarkIdByLocalId(
                        local_id = relationRow.bookmark_local_id,
                        remote_id = local.model.bookmarkRemoteId,
                        modified_at = updatedAt
                    )
                }
        }
    }

    private fun LocalModelMutation<LocalSyncCollectionAyahBookmark>.relationBookmarkRemoteId(): String? {
        model.bookmarkRemoteId
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val collectionRemoteId = model.collectionRemoteId ?: return null
        val remoteId = remoteID ?: return null
        val prefix = "$collectionRemoteId-"
        return if (remoteId.startsWith(prefix) && remoteId.length > prefix.length) {
            remoteId.removePrefix(prefix)
        } else {
            null
        }
    }

    private fun findBookmarkByDefaultRelationBookmarkRemoteId(bookmarkRemoteId: String): DatabaseBookmark? {
        return bookmarkQueries.value.getBookmarkByRemoteId(bookmarkRemoteId)
            .executeAsOneOrNull()
            ?: bookmarkQueries.value.getBookmarkByDefaultRelationBookmarkRemoteId(bookmarkRemoteId)
                .executeAsOneOrNull()
    }

    private fun clearCustomDeleteMutation(
        local: LocalModelMutation<LocalSyncCollectionAyahBookmark>,
        relationRow: DatabaseBookmarkCollection?,
        updatedAt: Long
    ) {
        relationRow ?: return
        if (!ackMatches(local, LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET)) {
            return
        }
        val ack = local.ack ?: return
        if (!relationRow.matchesSyncedSnapshot(local.model)) {
            return
        }
        if (relationRow.pending_op == "DELETED" &&
            relationRow.is_active == 0L &&
            relationRow.pending_version == ack.observedPendingVersion
        ) {
            bookmarkCollectionQueries.value.clearLocalMutationFor(
                id = relationRow.local_id,
                bookmark_remote_id = local.model.bookmarkRemoteId,
                collection_remote_id = local.model.collectionRemoteId,
                modified_at = updatedAt,
                pending_op = ack.observedPendingOp.name,
                pending_version = ack.observedPendingVersion
            )
            reconciler.pruneBookmarkIfOrphan(relationRow.bookmark_local_id)
        } else if (relationRow.pending_op == null && relationRow.is_active == 1L) {
            bookmarkCollectionQueries.value.markBookmarkCollectionForRecreation(
                id = relationRow.local_id,
                modified_at = updatedAt
            )
        }
    }

    private fun DatabaseBookmarkCollection.matchesSyncedSnapshot(bookmark: LocalSyncCollectionAyahBookmark): Boolean {
        return last_synced_bookmark_remote_id == bookmark.bookmarkRemoteId &&
            last_synced_collection_remote_id == bookmark.collectionRemoteId
    }

    private fun ackMatches(
        local: LocalModelMutation<LocalSyncCollectionAyahBookmark>,
        facet: String
    ): Boolean {
        val ack = local.ack ?: return false
        return ack.localID == local.localID &&
            ack.resource == LocalMutationResource.COLLECTION_BOOKMARK &&
            ack.facet == facet
    }

    private fun applyRemoteCollectionBookmarkUpsert(remote: RemoteModelMutation<RemoteCollectionBookmark>) {
        if (remote.model.collectionId == DEFAULT_COLLECTION_ID) {
            applyRemoteDefaultBookmarkUpsert(remote)
            return
        }

        val collection = collectionQueries.value
            .getCollectionByRemoteId(remote.model.collectionId)
            .executeAsOneOrNull()
        if (collection == null) {
            logger.w { "Skipping remote collection bookmark without local collection: remoteId=${remote.model.collectionId}" }
            return
        }
        val bookmarkLocalId = resolveBookmarkLocalId(remote.model, createIfMissing = true)
        if (bookmarkLocalId == null) {
            logger.w { "Skipping remote collection bookmark without local bookmark: remoteId=${remote.remoteID}" }
            return
        }
        val updatedAt = remote.model.lastUpdated.fromPlatform().toEpochMilliseconds()
        val bookmarkRemoteId = remote.model.bookmarkId
            ?: bookmarkQueries.value.getBookmarkByLocalId(bookmarkLocalId).executeAsOneOrNull()?.remote_id
        bookmarkCollectionQueries.value.persistRemoteBookmarkCollection(
            bookmark_local_id = bookmarkLocalId,
            collection_local_id = collection.local_id,
            bookmark_remote_id = bookmarkRemoteId,
            collection_remote_id = remote.model.collectionId,
            created_at = updatedAt,
            modified_at = updatedAt
        )
    }

    private fun applyRemoteDefaultBookmarkUpsert(remote: RemoteModelMutation<RemoteCollectionBookmark>) {
        val bookmarkLocalId = resolveBookmarkLocalId(
            bookmark = remote.model,
            createIfMissing = true,
            markDefaultForRecreationOnBackfill = false
        ) ?: return
        val bookmarkRow = bookmarkQueries.value.getBookmarkByLocalId(bookmarkLocalId).executeAsOneOrNull()
        if (bookmarkRow?.bookmark_pending_op == "DELETED") {
            return
        }
        val updatedAt = remote.model.lastUpdated.fromPlatform().toEpochMilliseconds()
        bookmarkQueries.value.setDefaultFromRemote(
            local_id = bookmarkLocalId,
            remote_id = remote.model.bookmarkId,
            is_in_default_collection = 1L,
            modified_at = updatedAt
        )
    }

    private fun applyRemoteCollectionBookmarkDeletion(remote: RemoteModelMutation<RemoteCollectionBookmark>) {
        if (remote.model.collectionId == DEFAULT_COLLECTION_ID) {
            val bookmarkLocalId = findBookmarkLocalIdWithoutRemoteIdBackfill(remote.model) ?: return
            val updatedAt = remote.model.lastUpdated.fromPlatform().toEpochMilliseconds()
            bookmarkQueries.value.setDefaultFromRemote(
                local_id = bookmarkLocalId,
                remote_id = remote.model.bookmarkId,
                is_in_default_collection = 0L,
                modified_at = updatedAt
            )
            val row = bookmarkQueries.value.getBookmarkByLocalId(bookmarkLocalId).executeAsOneOrNull()
            if (row != null) {
                reconciler.pruneBookmarkIfOrphan(bookmarkLocalId)
            }
            return
        }

        val bookmarkRemoteId = remote.model.bookmarkId
        if (bookmarkRemoteId.isNullOrEmpty()) {
            val collection = collectionQueries.value
                .getCollectionByRemoteId(remote.model.collectionId)
                .executeAsOneOrNull()
            val bookmarkLocalId = findBookmarkLocalIdWithoutRemoteIdBackfill(remote.model)
            if (collection != null && bookmarkLocalId != null) {
                bookmarkCollectionQueries.value.markBookmarkCollectionDeleted(
                    bookmark_local_id = bookmarkLocalId,
                    collection_local_id = collection.local_id,
                    timestamp = remote.model.lastUpdated.fromPlatform().toEpochMilliseconds()
                )
            }
            return
        }
        val bookmarkLocalIdBySnapshot = bookmarkCollectionQueries.value.getBookmarkLocalIdBySnapshot(
            bookmark_remote_id = bookmarkRemoteId,
            collection_remote_id = remote.model.collectionId
        ).executeAsOneOrNull()
        if (bookmarkLocalIdBySnapshot != null) {
            if (!bookmarkLocalIdBySnapshot.matchesPayload(remote.model)) {
                logger.w {
                    "Ignoring remote collection bookmark delete with mismatched bookmarkId=$bookmarkRemoteId " +
                        "for ${remote.model.collectionId}"
                }
                return
            }
            bookmarkCollectionQueries.value.deleteRemoteBookmarkCollectionBySnapshot(
                bookmark_remote_id = bookmarkRemoteId,
                collection_remote_id = remote.model.collectionId
            )
            reconciler.pruneBookmarkIfOrphan(bookmarkLocalIdBySnapshot)
            return
        }

        val bookmarkLocalIdByCurrent = bookmarkCollectionQueries.value.getBookmarkLocalIdByCurrentRemoteIds(
            bookmark_remote_id = bookmarkRemoteId,
            collection_remote_id = remote.model.collectionId
        ).executeAsOneOrNull()
        if (bookmarkLocalIdByCurrent != null && !bookmarkLocalIdByCurrent.matchesPayload(remote.model)) {
            logger.w {
                "Ignoring remote collection bookmark delete with mismatched bookmarkId=$bookmarkRemoteId " +
                    "for ${remote.model.collectionId}"
            }
            return
        }
        bookmarkCollectionQueries.value.deleteBookmarkCollectionByCurrentRemoteIds(
            bookmark_remote_id = bookmarkRemoteId,
            collection_remote_id = remote.model.collectionId
        )
        if (bookmarkLocalIdByCurrent != null) {
            reconciler.pruneBookmarkIfOrphan(bookmarkLocalIdByCurrent)
            return
        }

        val collection = collectionQueries.value
            .getCollectionByRemoteId(remote.model.collectionId)
            .executeAsOneOrNull() ?: return
        val bookmarkLocalIdByLocation = findBookmarkLocalIdWithoutRemoteIdBackfill(remote.model) ?: return
        bookmarkCollectionQueries.value
            .getCollectionBookmarkFor(bookmarkLocalIdByLocation, collection.local_id)
            .executeAsOneOrNull() ?: return
        bookmarkCollectionQueries.value.deleteUnsyncedBookmarkCollectionByLocalIds(
            bookmark_local_id = bookmarkLocalIdByLocation,
            collection_local_id = collection.local_id
        )
        reconciler.pruneBookmarkIfOrphan(bookmarkLocalIdByLocation)
    }

    private fun resolveBookmarkLocalId(
        bookmark: RemoteCollectionBookmark,
        createIfMissing: Boolean,
        markDefaultForRecreationOnBackfill: Boolean = true
    ): Long? {
        return when (bookmark) {
            is RemoteCollectionBookmark.Page -> null
            is RemoteCollectionBookmark.Ayah -> {
                val existingByRemote = bookmark.bookmarkId
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { bookmarkQueries.value.getBookmarkByRemoteId(it).executeAsOneOrNull() }
                if (existingByRemote != null) {
                    if (!existingByRemote.matches(bookmark)) {
                        logger.w {
                            "Skipping remote collection bookmark with mismatched bookmarkId=${bookmark.bookmarkId} " +
                                "for ${bookmark.sura}:${bookmark.ayah}"
                        }
                        return null
                    }
                    reactivateDeletedRemoteBookmarkIfNeeded(bookmark, existingByRemote)
                    return existingByRemote.local_id
                }

                val existingByLocation = bookmarkQueries.value
                    .getBookmarkForAyah(bookmark.sura.toLong(), bookmark.ayah.toLong())
                    .executeAsOneOrNull()
                if (existingByLocation != null) {
                    if (!bookmark.bookmarkId.isNullOrEmpty() && existingByLocation.remote_id == null) {
                        if (markDefaultForRecreationOnBackfill) {
                            upsertRelationBookmarkRemoteId(
                                bookmark = bookmark,
                                bookmarkLocalId = existingByLocation.local_id,
                                updatedAt = bookmark.lastUpdated.fromPlatform().toEpochMilliseconds()
                            )
                        } else {
                            bookmarkQueries.value.attachRemoteBookmarkIdByLocalId(
                                local_id = existingByLocation.local_id,
                                remote_id = bookmark.bookmarkId,
                                modified_at = bookmark.lastUpdated.fromPlatform().toEpochMilliseconds()
                            )
                        }
                    } else if (!bookmark.bookmarkId.isNullOrEmpty() && existingByLocation.remote_id != bookmark.bookmarkId) {
                        logger.w {
                            "Skipping stale remote collection bookmark for ${bookmark.sura}:${bookmark.ayah}: " +
                                "payloadBookmarkId=${bookmark.bookmarkId}, localRemoteId=${existingByLocation.remote_id}"
                        }
                        return null
                    }
                    return existingByLocation.local_id
                }

                if (!createIfMissing) {
                    return null
                }

                val updatedAt = bookmark.lastUpdated.fromPlatform().toEpochMilliseconds()
                bookmarkQueries.value.upsertAyahBookmark(
                    remote_id = bookmark.bookmarkId,
                    ayah_id = getAyahId(bookmark.sura, bookmark.ayah).toLong(),
                    sura = bookmark.sura.toLong(),
                    ayah = bookmark.ayah.toLong(),
                    timestamp = updatedAt
                )
                bookmarkQueries.value.getBookmarkForAyah(bookmark.sura.toLong(), bookmark.ayah.toLong())
                    .executeAsOneOrNull()
                    ?.local_id
            }
        }
    }

    private fun DatabaseBookmark.matches(bookmark: RemoteCollectionBookmark.Ayah): Boolean {
        return bookmark_type == "AYAH" &&
            sura == bookmark.sura.toLong() &&
            ayah == bookmark.ayah.toLong()
    }

    private fun Long.matchesPayload(bookmark: RemoteCollectionBookmark): Boolean {
        return when (bookmark) {
            is RemoteCollectionBookmark.Page -> false
            is RemoteCollectionBookmark.Ayah ->
                bookmarkQueries.value.getBookmarkByLocalId(this)
                    .executeAsOneOrNull()
                    ?.matches(bookmark) == true
        }
    }

    private fun upsertRelationBookmarkRemoteId(
        bookmark: RemoteCollectionBookmark.Ayah,
        bookmarkLocalId: Long,
        updatedAt: Long
    ) {
        bookmarkQueries.value.upsertAyahBookmark(
            remote_id = bookmark.bookmarkId,
            ayah_id = getAyahId(bookmark.sura, bookmark.ayah).toLong(),
            sura = bookmark.sura.toLong(),
            ayah = bookmark.ayah.toLong(),
            timestamp = updatedAt
        )
        bookmarkQueries.value.markDefaultRelationForRecreation(
            local_id = bookmarkLocalId,
            modified_at = updatedAt
        )
    }

    private fun upsertRelationBookmarkRemoteId(
        bookmark: LocalSyncCollectionAyahBookmark,
        bookmarkLocalId: Long,
        updatedAt: Long
    ) {
        bookmarkQueries.value.upsertAyahBookmark(
            remote_id = bookmark.bookmarkRemoteId,
            ayah_id = getAyahId(bookmark.sura, bookmark.ayah).toLong(),
            sura = bookmark.sura.toLong(),
            ayah = bookmark.ayah.toLong(),
            timestamp = updatedAt
        )
        bookmarkQueries.value.markDefaultRelationForRecreation(
            local_id = bookmarkLocalId,
            modified_at = updatedAt
        )
    }

    private fun reactivateDeletedRemoteBookmarkIfNeeded(
        bookmark: RemoteCollectionBookmark.Ayah,
        row: DatabaseBookmark
    ) {
        if (row.bookmark_pending_op == "DELETED") {
            return
        }
        if (row.deleted != 1L) {
            return
        }

        bookmarkQueries.value.upsertAyahBookmark(
            remote_id = bookmark.bookmarkId,
            ayah_id = getAyahId(bookmark.sura, bookmark.ayah).toLong(),
            sura = bookmark.sura.toLong(),
            ayah = bookmark.ayah.toLong(),
            timestamp = bookmark.lastUpdated.fromPlatform().toEpochMilliseconds()
        )
    }

    private fun findBookmarkLocalIdWithoutRemoteIdBackfill(bookmark: RemoteCollectionBookmark): Long? {
        return when (bookmark) {
            is RemoteCollectionBookmark.Page -> null
            is RemoteCollectionBookmark.Ayah -> {
                val existingByRemote = bookmark.bookmarkId
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { bookmarkQueries.value.getBookmarkByRemoteId(it).executeAsOneOrNull() }
                if (existingByRemote != null) {
                    if (!existingByRemote.matches(bookmark)) {
                        logger.w {
                            "Ignoring remote collection bookmark delete with mismatched bookmarkId=${bookmark.bookmarkId} " +
                                "for ${bookmark.sura}:${bookmark.ayah}"
                        }
                        return null
                    }
                    return existingByRemote.local_id
                }

                val existingByLocation = bookmarkQueries.value
                    .getBookmarkForAyah(bookmark.sura.toLong(), bookmark.ayah.toLong())
                    .executeAsOneOrNull()
                    ?: return null
                if (bookmark.bookmarkId.isNullOrEmpty() ||
                    existingByLocation.remote_id.isNullOrEmpty() ||
                    existingByLocation.remote_id == bookmark.bookmarkId
                ) {
                    existingByLocation.local_id
                } else {
                    null
                }
            }
        }
    }

    private fun toCollectionBookmark(
        bookmarkLocalId: Long,
        bookmarkRemoteId: String?,
        sura: Long?,
        ayah: Long?,
        collectionLocalId: Long,
        collectionRemoteId: String?,
        modifiedAt: Long,
        localId: Long,
        logMissingBookmark: Boolean
    ): CollectionAyahBookmark? {
        return collectionBookmarkFields(
            bookmarkLocalId = bookmarkLocalId,
            bookmarkRemoteId = bookmarkRemoteId,
            sura = sura,
            ayah = ayah,
            collectionLocalId = collectionLocalId,
            collectionRemoteId = collectionRemoteId,
            modifiedAt = modifiedAt,
            localId = localId,
            logMissingBookmark = logMissingBookmark
        )?.toCollectionBookmark()
    }

    private fun toLocalSyncCollectionBookmark(
        bookmarkLocalId: Long,
        bookmarkRemoteId: String?,
        sura: Long?,
        ayah: Long?,
        collectionLocalId: Long,
        collectionRemoteId: String?,
        modifiedAt: Long,
        createdAt: Long?,
        localId: Long,
        logMissingBookmark: Boolean
    ): LocalSyncCollectionAyahBookmark? {
        return collectionBookmarkFields(
            bookmarkLocalId = bookmarkLocalId,
            bookmarkRemoteId = bookmarkRemoteId,
            sura = sura,
            ayah = ayah,
            collectionLocalId = collectionLocalId,
            collectionRemoteId = collectionRemoteId,
            modifiedAt = modifiedAt,
            localId = localId,
            logMissingBookmark = logMissingBookmark
        )?.toLocalSyncCollectionBookmark(
            createdAt = createdAt?.let { Instant.fromEpochMilliseconds(it).toPlatform() }
        )
    }

    private fun collectionBookmarkFields(
        bookmarkLocalId: Long,
        bookmarkRemoteId: String?,
        sura: Long?,
        ayah: Long?,
        collectionLocalId: Long,
        collectionRemoteId: String?,
        modifiedAt: Long,
        localId: Long,
        logMissingBookmark: Boolean
    ): CollectionBookmarkFields? {
        val updatedAt = Instant.fromEpochMilliseconds(modifiedAt).toPlatform()
        val suraValue = sura?.toInt()
        val ayahValue = ayah?.toInt()
        if (suraValue == null || ayahValue == null) {
            if (logMissingBookmark) {
                logger.w { "Skipping collection bookmark without local ayah bookmark: localId=$localId" }
            }
            return null
        }
        return CollectionBookmarkFields(
            collectionLocalId = collectionLocalId.toString(),
            collectionRemoteId = collectionRemoteId,
            bookmarkLocalId = bookmarkLocalId.toString(),
            bookmarkRemoteId = bookmarkRemoteId,
            sura = suraValue,
            ayah = ayahValue,
            lastUpdated = updatedAt,
            localId = localId.toString()
        )
    }

    private fun DatabaseBookmark.toDefaultCollectionBookmark(
        relationBookmarkRemoteId: String? = remote_id
    ): CollectionAyahBookmark {
        return defaultCollectionBookmark(
            bookmarkLocalId = local_id,
            bookmarkRemoteId = relationBookmarkRemoteId,
            sura = sura,
            ayah = ayah,
            modifiedAt = default_modified_at ?: modified_at
        )
    }

    private fun defaultLocalSyncCollectionBookmark(
        bookmarkLocalId: Long,
        bookmarkRemoteId: String?,
        sura: Long?,
        ayah: Long?,
        modifiedAt: Long,
        createdAt: Long
    ): LocalSyncCollectionAyahBookmark {
        return defaultCollectionBookmarkFields(
            bookmarkLocalId = bookmarkLocalId,
            bookmarkRemoteId = bookmarkRemoteId,
            sura = sura,
            ayah = ayah,
            modifiedAt = modifiedAt
        ).toLocalSyncCollectionBookmark(
            createdAt = Instant.fromEpochMilliseconds(createdAt).toPlatform()
        )
    }

    private fun defaultCollectionBookmark(
        bookmarkLocalId: Long,
        bookmarkRemoteId: String?,
        sura: Long?,
        ayah: Long?,
        modifiedAt: Long
    ): CollectionAyahBookmark {
        return defaultCollectionBookmarkFields(
            bookmarkLocalId = bookmarkLocalId,
            bookmarkRemoteId = bookmarkRemoteId,
            sura = sura,
            ayah = ayah,
            modifiedAt = modifiedAt
        ).toCollectionBookmark()
    }

    private fun defaultCollectionBookmarkFields(
        bookmarkLocalId: Long,
        bookmarkRemoteId: String?,
        sura: Long?,
        ayah: Long?,
        modifiedAt: Long
    ): CollectionBookmarkFields {
        val updatedAt = Instant.fromEpochMilliseconds(modifiedAt).toPlatform()
        return CollectionBookmarkFields(
            collectionLocalId = DEFAULT_COLLECTION_ID,
            collectionRemoteId = DEFAULT_COLLECTION_ID,
            bookmarkLocalId = bookmarkLocalId.toString(),
            bookmarkRemoteId = bookmarkRemoteId,
            sura = requireNotNull(sura).toInt(),
            ayah = requireNotNull(ayah).toInt(),
            lastUpdated = updatedAt,
            localId = defaultLocalId(bookmarkLocalId)
        )
    }

    private fun CollectionBookmarkFields.toCollectionBookmark(): CollectionAyahBookmark {
        return CollectionAyahBookmark(
            collectionLocalId = collectionLocalId,
            collectionRemoteId = collectionRemoteId,
            bookmarkLocalId = bookmarkLocalId,
            bookmarkRemoteId = bookmarkRemoteId,
            sura = sura,
            ayah = ayah,
            lastUpdated = lastUpdated,
            localId = localId
        )
    }

    private fun CollectionBookmarkFields.toLocalSyncCollectionBookmark(
        createdAt: PlatformDateTime?
    ): LocalSyncCollectionAyahBookmark {
        return LocalSyncCollectionAyahBookmark(
            collectionLocalId = collectionLocalId,
            collectionRemoteId = collectionRemoteId,
            bookmarkLocalId = bookmarkLocalId,
            bookmarkRemoteId = bookmarkRemoteId,
            sura = sura,
            ayah = ayah,
            lastUpdated = lastUpdated,
            localId = localId,
            createdAt = createdAt
        )
    }

    private fun getAyahId(sura: Int, ayah: Int): Int {
        return QuranData.getAyahId(sura, ayah)
    }
}

private data class CollectionBookmarkFields(
    val collectionLocalId: String,
    val collectionRemoteId: String?,
    val bookmarkLocalId: String,
    val bookmarkRemoteId: String?,
    val sura: Int,
    val ayah: Int,
    val lastUpdated: PlatformDateTime,
    val localId: String
)

private const val DEFAULT_LOCAL_ID_PREFIX = "default:"

private fun defaultLocalId(bookmarkLocalId: Long): String = "$DEFAULT_LOCAL_ID_PREFIX$bookmarkLocalId"

private fun collectionBookmarkRemoteId(collectionId: String, bookmarkId: String): String {
    return "$collectionId-$bookmarkId"
}
