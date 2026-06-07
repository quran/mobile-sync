package com.quran.shared.persistence.repository.collectionbookmark.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.touchlab.kermit.Logger
import com.quran.shared.di.AppScope
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.input.RemoteCollectionBookmark
import com.quran.shared.persistence.model.AyahBookmark
import com.quran.shared.persistence.model.CollectionAyahBookmark
import com.quran.shared.persistence.model.DEFAULT_COLLECTION_ID
import com.quran.shared.persistence.model.DatabaseBookmark
import com.quran.shared.persistence.model.DatabaseBookmarkCollection
import com.quran.shared.persistence.repository.bookmark.BookmarkDependencyReconciler
import com.quran.shared.persistence.repository.bookmark.extension.toAyahBookmark
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.QuranData
import com.quran.shared.persistence.util.SQLITE_MAX_BIND_PARAMETERS
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

    override suspend fun fetchMutatedCollectionBookmarks(): List<LocalModelMutation<CollectionAyahBookmark>> {
        return withContext(Dispatchers.IO) {
            val defaultMutations = bookmarkQueries.value.getDefaultPendingBookmarks()
                .executeAsList()
                .map { row ->
                    val mutation = if (row.default_pending_op == "DELETED") Mutation.DELETED else Mutation.CREATED
                    LocalModelMutation(
                        mutation = mutation,
                        model = defaultCollectionBookmark(
                            bookmarkLocalId = row.local_id,
                            bookmarkRemoteId = row.remote_id,
                            sura = row.sura,
                            ayah = row.ayah,
                            modifiedAt = row.default_modified_at ?: row.modified_at
                        ),
                        remoteID = row.remote_id?.let { collectionBookmarkRemoteId(DEFAULT_COLLECTION_ID, it) },
                        localID = defaultLocalId(row.local_id)
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
                    val collectionBookmark = toCollectionBookmark(
                        bookmarkLocalId = record.bookmark_local_id,
                        bookmarkRemoteId = bookmarkRemoteId,
                        sura = record.sura,
                        ayah = record.ayah,
                        collectionLocalId = record.collection_local_id,
                        collectionRemoteId = collectionRemoteId,
                        modifiedAt = record.modified_at,
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
                        localID = record.local_id.toString()
                    )
                }
            defaultMutations + customMutations
        }
    }

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteCollectionBookmark>>,
        localMutationsToClear: List<LocalModelMutation<CollectionAyahBookmark>>
    ) {
        logger.i {
            "Applying remote collection bookmark changes with " +
                "${updatesToPersist.size} updates to persist and ${localMutationsToClear.size} local mutations to clear"
        }
        return withContext(Dispatchers.IO) {
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
        if (remoteIDs.isEmpty()) {
            return emptyMap()
        }

        return withContext(Dispatchers.IO) {
            val existentIDs = mutableSetOf<String>()
            remoteIDs.chunked(SQLITE_MAX_BIND_PARAMETERS).forEach { chunk ->
                existentIDs.addAll(
                    bookmarkCollectionQueries.value
                        .checkRemoteIDsExistence(chunk)
                        .executeAsList()
                        .mapNotNull { it.remote_id }
                )
                chunk.filter { it.startsWith("$DEFAULT_COLLECTION_ID-") }
                    .mapNotNullTo(existentIDs) { remoteId ->
                        val bookmarkRemoteId = remoteId.removePrefix("$DEFAULT_COLLECTION_ID-")
                        val bookmark = bookmarkQueries.value
                            .getBookmarkByRemoteId(bookmarkRemoteId)
                            .executeAsOneOrNull()
                        if (bookmark != null &&
                            bookmark.deleted == 0L &&
                            (bookmark.is_in_default_collection == 1L || bookmark.default_pending_op == "DELETED")
                        ) {
                            remoteId
                        } else {
                            null
                        }
                    }
            }

            remoteIDs.associateWith { existentIDs.contains(it) }
        }
    }

    override suspend fun fetchCollectionBookmarkByRemoteId(remoteId: String): CollectionAyahBookmark? {
        return withContext(Dispatchers.IO) {
            if (remoteId.startsWith("$DEFAULT_COLLECTION_ID-")) {
                val bookmarkRemoteId = remoteId.removePrefix("$DEFAULT_COLLECTION_ID-")
                val row = bookmarkQueries.value.getBookmarkByRemoteId(bookmarkRemoteId)
                    .executeAsOneOrNull()
                if (row != null &&
                    row.deleted == 0L &&
                    (row.is_in_default_collection == 1L || row.default_pending_op == "DELETED")
                ) {
                    return@withContext row.toDefaultCollectionBookmark()
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

    private fun clearLocalMutation(local: LocalModelMutation<CollectionAyahBookmark>) {
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
        if (relationRow?.pending_op != "CREATED" || relationRow.is_active != 1L) {
            return
        }
        bookmarkCollectionQueries.value.clearLocalMutationFor(
            id = localId,
            bookmark_remote_id = local.model.bookmarkRemoteId,
            collection_remote_id = local.model.collectionRemoteId,
            modified_at = updatedAt
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
        local: LocalModelMutation<CollectionAyahBookmark>,
        bookmarkLocalId: Long,
        updatedAt: Long
    ) {
        val row = bookmarkQueries.value.getBookmarkByLocalId(bookmarkLocalId).executeAsOneOrNull() ?: return
        if (local.mutation == Mutation.DELETED) {
            if (row.default_pending_op == "DELETED" &&
                row.is_in_default_collection == 0L
            ) {
                bookmarkQueries.value.clearDefaultPending(
                    local_id = bookmarkLocalId,
                    remote_id = null,
                    modified_at = updatedAt
                )
                if (row.remote_id == local.model.bookmarkRemoteId) {
                    reconciler.pruneBookmarkIfOrphan(bookmarkLocalId)
                }
            } else if (row.default_pending_op == null &&
                row.is_in_default_collection == 1L &&
                row.remote_id == local.model.bookmarkRemoteId
            ) {
                bookmarkQueries.value.markDefaultRelationForRecreation(
                    local_id = bookmarkLocalId,
                    modified_at = updatedAt
                )
            }
            return
        }

        if (row.default_pending_op != "CREATED" || row.is_in_default_collection != 1L) {
            return
        }
        bookmarkQueries.value.clearDefaultPending(
            local_id = bookmarkLocalId,
            remote_id = local.model.bookmarkRemoteId,
            modified_at = updatedAt
        )
    }

    private fun clearCustomDeleteMutation(
        local: LocalModelMutation<CollectionAyahBookmark>,
        relationRow: DatabaseBookmarkCollection?,
        updatedAt: Long
    ) {
        relationRow ?: return
        if (!relationRow.matchesSyncedSnapshot(local.model)) {
            return
        }
        if (relationRow.pending_op == "DELETED" && relationRow.is_active == 0L) {
            bookmarkCollectionQueries.value.clearLocalMutationFor(
                id = relationRow.local_id,
                bookmark_remote_id = local.model.bookmarkRemoteId,
                collection_remote_id = local.model.collectionRemoteId,
                modified_at = updatedAt
            )
            reconciler.pruneBookmarkIfOrphan(relationRow.bookmark_local_id)
        } else if (relationRow.pending_op == null && relationRow.is_active == 1L) {
            bookmarkCollectionQueries.value.markBookmarkCollectionForRecreation(
                id = relationRow.local_id,
                modified_at = updatedAt
            )
        }
    }

    private fun DatabaseBookmarkCollection.matchesSyncedSnapshot(bookmark: CollectionAyahBookmark): Boolean {
        return last_synced_bookmark_remote_id == bookmark.bookmarkRemoteId &&
            last_synced_collection_remote_id == bookmark.collectionRemoteId
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
        val bookmarkLocalId = resolveBookmarkLocalId(remote.model, createIfMissing = true) ?: return
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
        createIfMissing: Boolean
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
                        upsertRelationBookmarkRemoteId(
                            bookmark = bookmark,
                            bookmarkLocalId = existingByLocation.local_id,
                            updatedAt = bookmark.lastUpdated.fromPlatform().toEpochMilliseconds()
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
        bookmark: CollectionAyahBookmark,
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
        if (row.deleted != 1L && row.bookmark_pending_op != "DELETED") {
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
        val updatedAt = Instant.fromEpochMilliseconds(modifiedAt).toPlatform()
        val suraValue = sura?.toInt()
        val ayahValue = ayah?.toInt()
        if (suraValue == null || ayahValue == null) {
            if (logMissingBookmark) {
                logger.w { "Skipping collection bookmark without local ayah bookmark: localId=$localId" }
            }
            return null
        }
        return CollectionAyahBookmark(
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

    private fun DatabaseBookmark.toDefaultCollectionBookmark(): CollectionAyahBookmark {
        return defaultCollectionBookmark(
            bookmarkLocalId = local_id,
            bookmarkRemoteId = remote_id,
            sura = sura,
            ayah = ayah,
            modifiedAt = default_modified_at ?: modified_at
        )
    }

    private fun defaultCollectionBookmark(
        bookmarkLocalId: Long,
        bookmarkRemoteId: String?,
        sura: Long?,
        ayah: Long?,
        modifiedAt: Long
    ): CollectionAyahBookmark {
        val updatedAt = Instant.fromEpochMilliseconds(modifiedAt).toPlatform()
        return CollectionAyahBookmark(
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

    private fun getAyahId(sura: Int, ayah: Int): Int {
        return QuranData.getAyahId(sura, ayah)
    }
}

private const val DEFAULT_LOCAL_ID_PREFIX = "default:"

private fun defaultLocalId(bookmarkLocalId: Long): String = "$DEFAULT_LOCAL_ID_PREFIX$bookmarkLocalId"

private fun collectionBookmarkRemoteId(collectionId: String, bookmarkId: String): String {
    return "$collectionId-$bookmarkId"
}
