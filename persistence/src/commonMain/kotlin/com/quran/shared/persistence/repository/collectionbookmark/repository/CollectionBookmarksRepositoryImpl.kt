package com.quran.shared.persistence.repository.collectionbookmark.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.touchlab.kermit.Logger
import com.quran.shared.di.AppScope
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
import com.quran.shared.persistence.model.AyahHighlight
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.CollectionAyahBookmark
import com.quran.shared.persistence.model.DatabaseBookmark
import com.quran.shared.persistence.model.DatabaseBookmarkCollection
import com.quran.shared.persistence.model.highlightColorForCollectionName
import com.quran.shared.persistence.repository.PersistenceWriteBoundaryGuard
import com.quran.shared.persistence.repository.buildRemoteResourceExistenceMap
import com.quran.shared.persistence.repository.bookmark.BookmarkDependencyReconciler
import com.quran.shared.persistence.repository.bookmark.activeSavedCollectionIdsForBookmark
import com.quran.shared.persistence.repository.bookmark.extension.toAyahBookmark
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.currentEpochMilliseconds
import com.quran.shared.persistence.util.currentPlatformDateTime
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toEpochMillisecondsFromPlatform
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
    private val highlightsRepository = AyahHighlightsRepository(database, reconciler)

    /** A replayed relation create whose final active state can reactivate or timestamp its parent. */
    private data class RemoteRelationActivationCandidate(
        val bookmarkLocalId: Long,
        val bookmarkRemoteId: String?,
        val collectionLocalId: Long,
        val updatedAt: Long,
        val isSavedCollection: Boolean
    )

    override fun getHighlightsFlow(): Flow<List<AyahHighlight>> = highlightsRepository.getHighlightsFlow()

    override suspend fun setHighlight(
        sura: Int,
        ayah: Int,
        color: AyahHighlightColor,
        timestamp: PlatformDateTime
    ): AyahHighlight = highlightsRepository.setHighlight(sura, ayah, color, timestamp)

    override suspend fun removeHighlight(sura: Int, ayah: Int): Boolean =
        removeHighlight(sura, ayah, currentPlatformDateTime())

    override suspend fun removeHighlight(
        sura: Int,
        ayah: Int,
        timestamp: PlatformDateTime
    ): Boolean = highlightsRepository.removeHighlight(sura, ayah, timestamp)

    override suspend fun getBookmarksForCollection(collectionId: String): List<CollectionAyahBookmark> {
        return withContext(Dispatchers.IO) {
            bookmarkCollectionQueries.value
                .getCollectionBookmarksForCollectionWithDetails(collection_local_id = collectionId.toLong())
                .executeAsList()
                .mapNotNull { record ->
                    toCollectionBookmark(
                        bookmarkLocalId = record.bookmark_local_id,
                        bookmarkRemoteId = record.bookmark_remote_id,
                        sura = record.sura,
                        ayah = record.ayah,
                        collectionLocalId = record.collection_local_id,
                        collectionRemoteId = record.collection_remote_id,
                        membershipModifiedAt = record.modified_at,
                        bookmarkModifiedAt = record.bookmark_last_updated_at,
                        bookmarkCreatedAt = record.bookmark_added_at,
                        localId = record.local_id,
                        logMissingBookmark = false
                    )
                }
        }
    }

    override fun getBookmarksForCollectionFlow(collectionId: String): Flow<List<CollectionAyahBookmark>> {
        return bookmarkCollectionQueries.value
            .getCollectionBookmarksForCollectionWithDetails(collection_local_id = collectionId.toLong())
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
                        membershipModifiedAt = record.modified_at,
                        bookmarkModifiedAt = record.bookmark_last_updated_at,
                        bookmarkCreatedAt = record.bookmark_added_at,
                        localId = record.local_id,
                        logMissingBookmark = false
                    )
                }
            }
    }

    override suspend fun addAyahBookmarkToCollection(
        collectionId: String,
        sura: Int,
        ayah: Int
    ): CollectionAyahBookmark {
        return addAyahBookmarkToCollection(collectionId, sura, ayah, currentPlatformDateTime())
    }

    override suspend fun addAyahBookmarkToCollection(
        collectionId: String,
        sura: Int,
        ayah: Int,
        timestamp: PlatformDateTime
    ): CollectionAyahBookmark {
        return addAyahBookmarkToCollectionWithTimestampMillis(
            collectionId,
            sura,
            ayah,
            timestamp.toEpochMillisecondsFromPlatform()
        )
    }

    private suspend fun addAyahBookmarkToCollectionWithTimestampMillis(
        collectionId: String,
        sura: Int,
        ayah: Int,
        timestampMillis: Long
    ): CollectionAyahBookmark {
        return withContext(Dispatchers.IO) {
            var created: CollectionAyahBookmark? = null
            database.transaction {
                val collection = collectionQueries.value
                    .getCollectionByLocalId(collectionId.toLong())
                    .executeAsOneOrNull()
                requireNotNull(collection) { "Collection not found for id=$collectionId." }
                require(collection.deleted == 0L) { "Collection is deleted for id=$collectionId." }
                require(highlightColorForCollectionName(collection.name) == null) {
                    "Highlight collections must be changed through setHighlight."
                }

                var bookmark = bookmarkQueries.value
                    .getBookmarkForAyah(sura.toLong(), ayah.toLong())
                    .executeAsOneOrNull()
                val hadActiveBookmark = bookmark?.deleted == 0L
                val currentSavedCollectionIds = if (hadActiveBookmark) {
                    database.activeSavedCollectionIdsForBookmark(requireNotNull(bookmark).local_id)
                } else {
                    emptySet()
                }
                if (!hadActiveBookmark) {
                    bookmarkQueries.value.upsertAyahBookmark(
                        remote_id = null,
                        sura = sura.toLong(),
                        ayah = ayah.toLong(),
                        created_at = timestampMillis,
                        modified_at = timestampMillis
                    )
                    bookmark = bookmarkQueries.value
                        .getBookmarkForAyah(sura.toLong(), ayah.toLong())
                        .executeAsOneOrNull()
                }
                val activeBookmark = requireNotNull(bookmark) {
                    "Expected ayah bookmark for $sura:$ayah before linking."
                }

                bookmarkCollectionQueries.value.addBookmarkToCollection(
                    bookmark_local_id = activeBookmark.local_id,
                    collection_local_id = collection.local_id,
                    timestamp = timestampMillis
                )
                val activatedMembership = bookmarkCollectionQueries.value
                    .getCollectionBookmarkFor(activeBookmark.local_id, collection.local_id)
                    .executeAsOneOrNull()
                if (hadActiveBookmark &&
                    currentSavedCollectionIds.isEmpty() &&
                    activatedMembership?.is_active == 1L
                ) {
                    bookmarkQueries.value.touchBookmarkForFirstSavedMembership(
                        local_id = activeBookmark.local_id,
                        modified_at = timestampMillis
                    )
                }
                reconciler.reconcile(timestampMillis)
                val record = bookmarkCollectionQueries.value
                    .getCollectionBookmarksForCollectionWithDetails(collection.local_id)
                    .executeAsList()
                    .first { it.bookmark_local_id == activeBookmark.local_id }
                created = toCollectionBookmark(
                    bookmarkLocalId = record.bookmark_local_id,
                    bookmarkRemoteId = record.bookmark_remote_id,
                    sura = record.sura,
                    ayah = record.ayah,
                    collectionLocalId = record.collection_local_id,
                    collectionRemoteId = record.collection_remote_id,
                    membershipModifiedAt = record.modified_at,
                    bookmarkModifiedAt = record.bookmark_last_updated_at,
                    bookmarkCreatedAt = record.bookmark_added_at,
                    localId = record.local_id,
                    logMissingBookmark = true
                )
            }
            requireNotNull(created)
        }
    }

    override suspend fun removeAyahBookmarkFromCollection(collectionAyahBookmark: CollectionAyahBookmark): Boolean {
        return withContext(Dispatchers.IO) {
            val timestampMillis = currentEpochMilliseconds()
            database.transaction {
                bookmarkCollectionQueries.value.markBookmarkCollectionDeleted(
                    bookmark_local_id = collectionAyahBookmark.bookmarkId.toLong(),
                    collection_local_id = collectionAyahBookmark.collectionId.toLong(),
                    timestamp = timestampMillis
                )
                reconciler.reconcile(timestampMillis)
            }
            true
        }
    }

    override suspend fun fetchMutatedCollectionBookmarks(): List<LocalModelMutation<LocalSyncCollectionAyahBookmark>> {
        return withContext(Dispatchers.IO) {
            bookmarkCollectionQueries.value.getUnsyncedCollectionBookmarksWithDetails()
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
                val bookmarkLocalIdsToPrune = mutableSetOf<Long>()
                localMutationsToClear.forEach { local ->
                    clearLocalMutation(local)?.let(bookmarkLocalIdsToPrune::add)
                }

                val savedBookmarkLocalIdsAtBatchStart = updatesToPersist
                    .asSequence()
                    .map { it.model }
                    .filterIsInstance<RemoteCollectionBookmark.Ayah>()
                    .map { bookmark -> bookmark.sura to bookmark.ayah }
                    .distinct()
                    .mapNotNull { (sura, ayah) ->
                        bookmarkQueries.value
                            .getBookmarkForAyah(sura.toLong(), ayah.toLong())
                            .executeAsOneOrNull()
                            ?.local_id
                    }
                    .filter { bookmarkLocalId ->
                        database.activeSavedCollectionIdsForBookmark(bookmarkLocalId).isNotEmpty()
                    }
                    .toSet()
                val recreatedBookmarkRemoteIds = updatesToPersist
                    .asSequence()
                    .filter { it.mutation == Mutation.CREATED }
                    .mapNotNull { remote ->
                        (remote.model as? RemoteCollectionBookmark.Ayah)
                            ?.bookmarkId
                            ?.takeIf(String::isNotEmpty)
                    }
                    .toSet()
                // Different parent identities must vacate the unique ayah location before creates.
                // Deletes recreated with the same ID stay in replay order to preserve their row and timestamps.
                val deletesToApplyBeforeCreates = updatesToPersist
                    .asSequence()
                    .filter { it.mutation == Mutation.DELETED }
                    .filter { remote ->
                        val bookmarkRemoteId = (remote.model as? RemoteCollectionBookmark.Ayah)
                            ?.bookmarkId
                            ?.takeIf(String::isNotEmpty)
                        bookmarkRemoteId != null && bookmarkRemoteId !in recreatedBookmarkRemoteIds
                    }
                    .toList()
                val deletedBookmarkParents = deletesToApplyBeforeCreates.mapNotNull { remote ->
                    val bookmark = remote.model as? RemoteCollectionBookmark.Ayah ?: return@mapNotNull null
                    applyRemoteCollectionBookmarkDeletion(remote)?.let { bookmarkLocalId ->
                        bookmarkLocalId to bookmark
                    }
                }
                deletedBookmarkParents
                    .distinctBy { (bookmarkLocalId, _) -> bookmarkLocalId }
                    .forEach { (bookmarkLocalId, deletedBookmark) ->
                        reconciler.pruneBookmarkIfOrphan(bookmarkLocalId)
                        releaseRemoteIdentityForLocalHighlightReplacement(
                            bookmarkLocalId = bookmarkLocalId,
                            deletedBookmark = deletedBookmark,
                            updatesToPersist = updatesToPersist
                        )
                    }

                val earliestCreationTimestampByAyah = earliestAcceptedRemoteCreationTimestampByAyah(updatesToPersist)
                val activationCandidates = mutableListOf<RemoteRelationActivationCandidate>()
                updatesToPersist.forEach { remote ->
                    when (remote.mutation) {
                        Mutation.CREATED -> applyRemoteCollectionBookmarkUpsert(
                            remote = remote,
                            missingBookmarkCreatedAt = (remote.model as? RemoteCollectionBookmark.Ayah)
                                ?.let { bookmark ->
                                    earliestCreationTimestampByAyah[bookmark.sura to bookmark.ayah]
                                }
                        )?.let(activationCandidates::add)
                        Mutation.DELETED -> {
                            if (remote !in deletesToApplyBeforeCreates) {
                                applyRemoteCollectionBookmarkDeletion(remote)
                                    ?.let(bookmarkLocalIdsToPrune::add)
                            }
                        }
                        Mutation.MODIFIED ->
                            throw RuntimeException("Unexpected MODIFIED remote modification for collection bookmarks.")
                    }
                }
                activationCandidates
                    .asSequence()
                    .filter { it.hasActiveRelationInActiveCollection() }
                    .filter { it.bookmarkRemoteId != null }
                    .distinctBy { it.bookmarkLocalId }
                    .forEach { candidate ->
                        bookmarkQueries.value.reactivateDeletedBookmarkForActiveRemoteRelation(
                            local_id = candidate.bookmarkLocalId,
                            remote_id = requireNotNull(candidate.bookmarkRemoteId)
                        )
                    }
                // Keep same-ID parent identity stable until the complete remote relation batch has replayed.
                // Reconciliation normalizes retained links before timestamping and orphan pruning.
                reconciler.reconcile()
                activationCandidates
                    .asSequence()
                    .filter(RemoteRelationActivationCandidate::isSavedCollection)
                    .filter { it.bookmarkLocalId !in savedBookmarkLocalIdsAtBatchStart }
                    .filter { it.hasActiveRelationInActiveCollection() }
                    .groupBy(RemoteRelationActivationCandidate::bookmarkLocalId)
                    .forEach { (bookmarkLocalId, candidates) ->
                        bookmarkQueries.value.touchBookmarkForFirstSavedMembership(
                            local_id = bookmarkLocalId,
                            modified_at = candidates.minOf(RemoteRelationActivationCandidate::updatedAt)
                        )
                    }
                bookmarkLocalIdsToPrune.forEach(reconciler::pruneBookmarkIfOrphan)
            }
        }
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> {
        return buildRemoteResourceExistenceMap(remoteIDs) { chunk ->
            bookmarkCollectionQueries.value
                .checkRemoteIDsExistence(chunk)
                .executeAsList()
                .mapNotNull { it.remote_id }
        }
    }

    override suspend fun fetchCollectionBookmarkByRemoteId(remoteId: String): LocalSyncCollectionAyahBookmark? {
        return withContext(Dispatchers.IO) {
            bookmarkCollectionQueries.value.getCollectionBookmarkWithDetailsByRemoteId(remote_id = remoteId)
                .executeAsOneOrNull()
                ?.let { record ->
                    val matchedSnapshot = record.last_synced_collection_remote_id != null &&
                        record.last_synced_bookmark_remote_id != null &&
                        collectionBookmarkRemoteId(
                            record.last_synced_collection_remote_id,
                            record.last_synced_bookmark_remote_id
                        ) == remoteId
                    toLocalSyncCollectionBookmark(
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
                        createdAt = record.created_at,
                        localId = record.local_id,
                        logMissingBookmark = false
                    )
                }
        }
    }

    /**
     * Clears an acknowledged link mutation.
     *
     * @return a bookmark local ID to test for orphan pruning after the inbound batch, or `null`.
     */
    private fun clearLocalMutation(local: LocalModelMutation<LocalSyncCollectionAyahBookmark>): Long? {
        val updatedAt = local.model.lastUpdated.fromPlatform().toEpochMilliseconds()
        val localId = local.localID.toLongOrNull() ?: return null
        val relationRow = bookmarkCollectionQueries.value
            .getCollectionBookmarkByLocalId(localId)
            .executeAsOneOrNull()
        if (local.mutation == Mutation.DELETED) {
            return clearCustomDeleteMutation(local, relationRow, updatedAt)
        }
        if (relationRow?.pending_op == "DELETED" && relationRow.is_active == 0L) {
            bindCustomCreatedAckToPendingDelete(local, relationRow, updatedAt)
            return null
        }
        if (relationRow?.pending_op != "CREATED" || relationRow.is_active != 1L) {
            return null
        }
        if (!ackMatches(local, LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET)) {
            return null
        }
        val ack = local.ack ?: return null
        val relationBookmarkRemoteId = local.relationBookmarkRemoteId()
        if (local.mutation == Mutation.CREATED && relationBookmarkRemoteId.isNullOrEmpty()) {
            return null
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
                    bookmarkQueries.value.bindRemoteBookmarkIdPreservingTimestampsByLocalId(
                        local_id = bookmarkLocalId,
                        remote_id = requireNotNull(local.model.bookmarkRemoteId)
                    )
                }
        }
        return null
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
            collection_remote_id = local.model.collectionRemoteId
        )
        if (!local.model.bookmarkRemoteId.isNullOrEmpty()) {
            bookmarkQueries.value.getBookmarkByLocalId(relationRow.bookmark_local_id)
                .executeAsOneOrNull()
                ?.takeIf { it.remote_id == null }
                ?.let {
                    bookmarkQueries.value.bindRemoteBookmarkIdPreservingTimestampsByLocalId(
                        local_id = relationRow.bookmark_local_id,
                        remote_id = local.model.bookmarkRemoteId
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

    private fun clearCustomDeleteMutation(
        local: LocalModelMutation<LocalSyncCollectionAyahBookmark>,
        relationRow: DatabaseBookmarkCollection?,
        updatedAt: Long
    ): Long? {
        relationRow ?: return null
        if (!ackMatches(local, LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET)) {
            return null
        }
        val ack = local.ack ?: return null
        if (!relationRow.matchesSyncedSnapshot(local.model)) {
            return null
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
            return relationRow.bookmark_local_id
        } else if (relationRow.pending_op == null && relationRow.is_active == 1L) {
            bookmarkCollectionQueries.value.markBookmarkCollectionForRecreation(
                id = relationRow.local_id,
                modified_at = relationRow.modified_at
            )
        }
        return null
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

    /**
     * Preplans deterministic fallback creation times when relation sync must reconstruct a missing
     * bookmark parent. Only creates whose collection dependency is locally active participate.
     */
    private fun earliestAcceptedRemoteCreationTimestampByAyah(
        updates: List<RemoteModelMutation<RemoteCollectionBookmark>>
    ): Map<Pair<Int, Int>, Long> {
        val earliestByAyah = mutableMapOf<Pair<Int, Int>, Long>()
        updates.forEach { remote ->
            if (remote.mutation != Mutation.CREATED) {
                return@forEach
            }
            val bookmark = remote.model as? RemoteCollectionBookmark.Ayah ?: return@forEach
            val collection = collectionQueries.value
                .getCollectionByRemoteId(bookmark.collectionId)
                .executeAsOneOrNull()
                ?: return@forEach
            if (collection.deleted != 0L) {
                return@forEach
            }
            val ayah = bookmark.sura to bookmark.ayah
            val updatedAt = bookmark.lastUpdated.fromPlatform().toEpochMilliseconds()
            val createdAt = bookmark.createdAt?.fromPlatform()?.toEpochMilliseconds() ?: updatedAt
            earliestByAyah[ayah] = minOf(earliestByAyah[ayah] ?: createdAt, createdAt)
        }
        return earliestByAyah
    }

    /** Returns whether this candidate still represents an active link after the current replay step. */
    private fun RemoteRelationActivationCandidate.hasActiveRelationInActiveCollection(): Boolean {
        val collection = collectionQueries.value
            .getCollectionByLocalId(collectionLocalId)
            .executeAsOneOrNull()
        if (collection?.deleted != 0L) {
            return false
        }
        return bookmarkCollectionQueries.value
            .getCollectionBookmarkFor(bookmarkLocalId, collectionLocalId)
            .executeAsOneOrNull()
            ?.is_active == 1L
    }

    private fun applyRemoteCollectionBookmarkUpsert(
        remote: RemoteModelMutation<RemoteCollectionBookmark>,
        missingBookmarkCreatedAt: Long?
    ): RemoteRelationActivationCandidate? {
        val collection = collectionQueries.value
            .getCollectionByRemoteId(remote.model.collectionId)
            .executeAsOneOrNull()
        if (collection == null) {
            logger.w { "Skipping remote collection bookmark without local collection: remoteId=${remote.model.collectionId}" }
            return null
        }
        val updatedAt = remote.model.lastUpdated.fromPlatform().toEpochMilliseconds()
        val bookmarkLocalId = resolveBookmarkLocalId(
            bookmark = remote.model,
            missingBookmarkCreatedAt = missingBookmarkCreatedAt ?: updatedAt
        )
        if (bookmarkLocalId == null) {
            logger.w { "Skipping remote collection bookmark without local bookmark: remoteId=${remote.remoteID}" }
            return null
        }
        val isSavedCollection = collection.deleted == 0L &&
            highlightColorForCollectionName(collection.name) == null
        val createdAt = remote.model.createdAt?.fromPlatform()?.toEpochMilliseconds() ?: updatedAt
        val bookmarkRemoteId = remote.model.bookmarkId
            ?: bookmarkQueries.value.getBookmarkByLocalId(bookmarkLocalId).executeAsOneOrNull()?.remote_id
        bookmarkCollectionQueries.value.persistRemoteBookmarkCollection(
            bookmark_local_id = bookmarkLocalId,
            collection_local_id = collection.local_id,
            bookmark_remote_id = bookmarkRemoteId,
            collection_remote_id = remote.model.collectionId,
            created_at = createdAt,
            modified_at = updatedAt
        )
        return RemoteRelationActivationCandidate(
            bookmarkLocalId = bookmarkLocalId,
            bookmarkRemoteId = bookmarkRemoteId,
            collectionLocalId = collection.local_id,
            updatedAt = updatedAt,
            isSavedCollection = isSavedCollection
        )
    }

    /**
     * Applies an authoritative remote link deletion without pruning its parent mid-batch.
     *
     * @return the affected bookmark local ID to test for orphan pruning after batch replay, or
     * `null` when no authoritative relation was removed.
     */
    private fun applyRemoteCollectionBookmarkDeletion(
        remote: RemoteModelMutation<RemoteCollectionBookmark>
    ): Long? {
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
            return null
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
                return null
            }
            bookmarkCollectionQueries.value.deleteRemoteBookmarkCollectionBySnapshot(
                bookmark_remote_id = bookmarkRemoteId,
                collection_remote_id = remote.model.collectionId
            )
            return bookmarkLocalIdBySnapshot
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
            return null
        }
        bookmarkCollectionQueries.value.deleteBookmarkCollectionByCurrentRemoteIds(
            bookmark_remote_id = bookmarkRemoteId,
            collection_remote_id = remote.model.collectionId
        )
        if (bookmarkLocalIdByCurrent != null) {
            return bookmarkLocalIdByCurrent
        }

        val collection = collectionQueries.value
            .getCollectionByRemoteId(remote.model.collectionId)
            .executeAsOneOrNull() ?: return null
        val bookmarkLocalIdByLocation = findBookmarkLocalIdWithoutRemoteIdBackfill(remote.model) ?: return null
        bookmarkCollectionQueries.value
            .getCollectionBookmarkFor(bookmarkLocalIdByLocation, collection.local_id)
            .executeAsOneOrNull() ?: return null
        bookmarkCollectionQueries.value.deleteUnsyncedBookmarkCollectionByLocalIds(
            bookmark_local_id = bookmarkLocalIdByLocation,
            collection_local_id = collection.local_id
        )
        return bookmarkLocalIdByLocation
    }

    private fun resolveBookmarkLocalId(
        bookmark: RemoteCollectionBookmark,
        missingBookmarkCreatedAt: Long
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
                    return existingByRemote.local_id
                }

                val existingByLocation = bookmarkQueries.value
                    .getBookmarkForAyah(bookmark.sura.toLong(), bookmark.ayah.toLong())
                    .executeAsOneOrNull()
                if (existingByLocation != null) {
                    if (!bookmark.bookmarkId.isNullOrEmpty() && existingByLocation.remote_id == null) {
                        bookmarkQueries.value.bindRemoteBookmarkIdPreservingTimestampsByLocalId(
                            local_id = existingByLocation.local_id,
                            remote_id = bookmark.bookmarkId
                        )
                    } else if (!bookmark.bookmarkId.isNullOrEmpty() && existingByLocation.remote_id != bookmark.bookmarkId) {
                        logger.w {
                            "Skipping stale remote collection bookmark for ${bookmark.sura}:${bookmark.ayah}: " +
                                "payloadBookmarkId=${bookmark.bookmarkId}, localRemoteId=${existingByLocation.remote_id}"
                        }
                        return null
                    }
                    return existingByLocation.local_id
                }

                bookmarkQueries.value.upsertAyahBookmark(
                    remote_id = bookmark.bookmarkId,
                    sura = bookmark.sura.toLong(),
                    ayah = bookmark.ayah.toLong(),
                    created_at = missingBookmarkCreatedAt,
                    modified_at = missingBookmarkCreatedAt
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

    /**
     * Releases an old remote identity only for an unambiguous same-ayah replacement whose parent
     * is retained exclusively by local highlight links. Reading, saved, remote-backed, and
     * non-highlight pending facets keep ownership and leave the mismatched-ID guard in force.
     */
    private fun releaseRemoteIdentityForLocalHighlightReplacement(
        bookmarkLocalId: Long,
        deletedBookmark: RemoteCollectionBookmark.Ayah,
        updatesToPersist: List<RemoteModelMutation<RemoteCollectionBookmark>>
    ) {
        val oldRemoteId = deletedBookmark.bookmarkId?.takeIf(String::isNotEmpty) ?: return
        val replacementRemoteId = updatesToPersist
            .asSequence()
            .filter { it.mutation == Mutation.CREATED }
            .mapNotNull { it.model as? RemoteCollectionBookmark.Ayah }
            .filter { it.sura == deletedBookmark.sura && it.ayah == deletedBookmark.ayah }
            .mapNotNull { replacement ->
                val replacementRemoteId = replacement.bookmarkId?.takeIf(String::isNotEmpty)
                    ?.takeIf { it != oldRemoteId }
                    ?: return@mapNotNull null
                val hasActiveSavedCollection = collectionQueries.value
                    .getCollectionByRemoteId(replacement.collectionId)
                    .executeAsOneOrNull()
                    ?.let { collection ->
                        collection.deleted == 0L && highlightColorForCollectionName(collection.name) == null
                    } == true
                replacementRemoteId.takeIf { hasActiveSavedCollection }
            }
            .distinct()
            .singleOrNull()
            ?: return
        if (bookmarkQueries.value.getBookmarkByRemoteId(replacementRemoteId).executeAsOneOrNull() != null) {
            return
        }

        val bookmark = bookmarkQueries.value
            .getBookmarkByLocalId(bookmarkLocalId)
            .executeAsOneOrNull()
            ?: return
        if (bookmark.remote_id != oldRemoteId ||
            bookmark.bookmark_type != "AYAH" ||
            bookmark.sura != deletedBookmark.sura.toLong() ||
            bookmark.ayah != deletedBookmark.ayah.toLong() ||
            bookmark.deleted != 0L ||
            bookmark.is_reading != 0L ||
            bookmark.reading_modified_at != null ||
            bookmark.bookmark_pending_op != null ||
            bookmark.reading_pending_op != null
        ) {
            return
        }

        val activeRelations = bookmarkCollectionQueries.value
            .getCollectionBookmarksWithDetails()
            .executeAsList()
            .filter { relation -> relation.bookmark_local_id == bookmarkLocalId }
        val retainedRelationCount = bookmarkCollectionQueries.value
            .countRetainedForBookmark(bookmarkLocalId)
            .executeAsOne()
        val hasOnlyLocalHighlights = activeRelations.isNotEmpty() &&
            activeRelations.size.toLong() == retainedRelationCount &&
            activeRelations.all { relation ->
                highlightColorForCollectionName(relation.collection_name) != null &&
                    relation.collection_remote_id == null &&
                    relation.last_synced_bookmark_remote_id == null &&
                    relation.last_synced_collection_remote_id == null &&
                    (relation.pending_op == null || relation.pending_op == "CREATED")
            }
        if (!hasOnlyLocalHighlights) {
            return
        }

        bookmarkQueries.value.clearBookmarkRemoteIdByLocalId(
            local_id = bookmarkLocalId,
            remote_id = oldRemoteId
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
        membershipModifiedAt: Long,
        bookmarkModifiedAt: Long,
        bookmarkCreatedAt: Long,
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
            membershipModifiedAt = membershipModifiedAt,
            bookmarkModifiedAt = bookmarkModifiedAt,
            bookmarkCreatedAt = bookmarkCreatedAt,
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
            membershipModifiedAt = modifiedAt,
            bookmarkModifiedAt = modifiedAt,
            bookmarkCreatedAt = modifiedAt,
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
        membershipModifiedAt: Long,
        bookmarkModifiedAt: Long,
        bookmarkCreatedAt: Long,
        localId: Long,
        logMissingBookmark: Boolean
    ): CollectionBookmarkFields? {
        val membershipUpdatedAt = Instant.fromEpochMilliseconds(membershipModifiedAt).toPlatform()
        val bookmarkUpdatedAt = Instant.fromEpochMilliseconds(bookmarkModifiedAt).toPlatform()
        val bookmarkAddedAt = Instant.fromEpochMilliseconds(bookmarkCreatedAt).toPlatform()
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
            membershipLastUpdated = membershipUpdatedAt,
            bookmarkLastUpdated = bookmarkUpdatedAt,
            bookmarkAddedDate = bookmarkAddedAt,
            localId = localId.toString()
        )
    }

    private fun CollectionBookmarkFields.toCollectionBookmark(): CollectionAyahBookmark {
        return CollectionAyahBookmark(
            collectionId = collectionLocalId,
            bookmarkId = bookmarkLocalId,
            sura = sura,
            ayah = ayah,
            bookmarkLastUpdated = bookmarkLastUpdated,
            bookmarkAddedDate = bookmarkAddedDate
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
            lastUpdated = membershipLastUpdated,
            localId = localId,
            createdAt = createdAt
        )
    }
}

private data class CollectionBookmarkFields(
    val collectionLocalId: String,
    val collectionRemoteId: String?,
    val bookmarkLocalId: String,
    val bookmarkRemoteId: String?,
    val sura: Int,
    val ayah: Int,
    val membershipLastUpdated: PlatformDateTime,
    val bookmarkLastUpdated: PlatformDateTime,
    val bookmarkAddedDate: PlatformDateTime,
    val localId: String
)

private fun collectionBookmarkRemoteId(collectionId: String, bookmarkId: String): String {
    return "$collectionId-$bookmarkId"
}
