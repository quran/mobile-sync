package com.quran.shared.persistence.repository.collectionbookmark.repository

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.input.RemoteCollectionBookmark
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.model.CollectionBookmark
import com.quran.shared.persistence.model.DatabaseBookmarkCollection
import com.quran.shared.persistence.repository.bookmark.extension.toBookmark
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.time.Instant

class CollectionBookmarksRepositoryImpl(
    private val database: QuranDatabase
) : CollectionBookmarksRepository, CollectionBookmarksSynchronizationRepository {

    private val logger = Logger.withTag("CollectionBookmarksRepository")
    private val bookmarkCollectionQueries = lazy { database.bookmark_collectionsQueries }
    private val pageBookmarkQueries = lazy { database.page_bookmarksQueries }
    private val ayahBookmarkQueries = lazy { database.ayah_bookmarksQueries }
    private val collectionQueries = lazy { database.collectionsQueries }

    override suspend fun getBookmarksForCollection(collectionLocalId: String): List<CollectionBookmark> {
        return withContext(Dispatchers.IO) {
            bookmarkCollectionQueries.value
                .getCollectionBookmarksForCollection(collectionLocalId.toLong())
                .executeAsList()
                .mapNotNull { record ->
                    val collection = collectionQueries.value
                        .getCollectionByLocalId(record.collection_local_id)
                        .executeAsOneOrNull()
                    val bookmark = resolveBookmark(record)
                    if (collection == null || bookmark == null) {
                        null
                    } else {
                        record.toCollectionBookmark(
                            collectionRemoteId = collection.remote_id,
                            bookmark = bookmark
                        )
                    }
                }
        }
    }

    override suspend fun addBookmarkToCollection(collectionLocalId: String, bookmark: Bookmark): CollectionBookmark {
        return withContext(Dispatchers.IO) {
            val bookmarkType = bookmark.toCollectionBookmarkType()
            bookmarkCollectionQueries.value.addBookmarkToCollection(
                bookmark_local_id = bookmark.localId,
                bookmark_type = bookmarkType,
                collection_local_id = collectionLocalId.toLong()
            )
            val record = bookmarkCollectionQueries.value
                .getCollectionBookmarkFor(bookmark.localId, collectionLocalId.toLong())
                .executeAsOneOrNull()
            requireNotNull(record) {
                "Expected collection bookmark for collection=$collectionLocalId and bookmark=${bookmark.localId}."
            }
            val collection = collectionQueries.value
                .getCollectionByLocalId(collectionLocalId.toLong())
                .executeAsOneOrNull()
            record.toCollectionBookmark(
                collectionRemoteId = collection?.remote_id,
                bookmark = bookmark
            )
        }
    }

    override suspend fun removeBookmarkFromCollection(collectionLocalId: String, bookmark: Bookmark): Boolean {
        return withContext(Dispatchers.IO) {
            bookmarkCollectionQueries.value.deleteBookmarkFromCollection(
                bookmark_local_id = bookmark.localId,
                collection_local_id = collectionLocalId.toLong()
            )
            true
        }
    }

    override suspend fun fetchMutatedCollectionBookmarks(): List<LocalModelMutation<CollectionBookmark>> {
        return withContext(Dispatchers.IO) {
            bookmarkCollectionQueries.value.getUnsyncedCollectionBookmarks()
                .executeAsList()
                .mapNotNull { record ->
                    val collection = collectionQueries.value
                        .getCollectionByLocalId(record.collection_local_id)
                        .executeAsOneOrNull()
                    val collectionRemoteId = collection?.remote_id
                    if (collectionRemoteId.isNullOrEmpty()) {
                        logger.w { "Skipping collection bookmark without remote collection ID: localId=${record.local_id}" }
                        return@mapNotNull null
                    }
                    val bookmark = resolveBookmark(record)
                    if (bookmark == null) {
                        logger.w { "Skipping collection bookmark without local bookmark: localId=${record.local_id}" }
                        return@mapNotNull null
                    }
                    val mutation = if (record.deleted == 1L) Mutation.DELETED else Mutation.CREATED
                    LocalModelMutation(
                        mutation = mutation,
                        model = record.toCollectionBookmark(
                            collectionRemoteId = collectionRemoteId,
                            bookmark = bookmark
                        ),
                        remoteID = record.remote_id,
                        localID = record.local_id.toString()
                    )
                }
        }
    }

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteCollectionBookmark>>,
        localMutationsToClear: List<LocalModelMutation<CollectionBookmark>>
    ) {
        logger.i {
            "Applying remote collection bookmark changes with " +
                "${updatesToPersist.size} updates to persist and ${localMutationsToClear.size} local mutations to clear"
        }
        return withContext(Dispatchers.IO) {
            database.transaction {
                localMutationsToClear.forEach { local ->
                    if (local.mutation == Mutation.DELETED) {
                        bookmarkCollectionQueries.value.clearLocalMutationFor(id = local.localID.toLong())
                    }
                }

                updatesToPersist.forEach { remote ->
                    when (remote.mutation) {
                        Mutation.CREATED -> applyRemoteCollectionBookmarkUpsert(remote)
                        Mutation.DELETED -> applyRemoteCollectionBookmarkDeletion(remote)
                        Mutation.MODIFIED ->
                            throw RuntimeException("Unexpected MODIFIED remote modification for collection bookmarks.")
                    }
                }
            }
        }
    }

    private fun applyRemoteCollectionBookmarkUpsert(remote: RemoteModelMutation<RemoteCollectionBookmark>) {
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
        val (bookmarkType, updatedAt) = remote.model.toBookmarkTypeWithTimestamp()
        bookmarkCollectionQueries.value.persistRemoteBookmarkCollection(
            remote_id = remote.remoteID,
            bookmark_local_id = bookmarkLocalId.toString(),
            bookmark_type = bookmarkType,
            collection_local_id = collection.local_id,
            created_at = updatedAt,
            modified_at = updatedAt
        )
    }

    private fun applyRemoteCollectionBookmarkDeletion(remote: RemoteModelMutation<RemoteCollectionBookmark>) {
        val collection = collectionQueries.value
            .getCollectionByRemoteId(remote.model.collectionId)
            .executeAsOneOrNull()
        val bookmarkLocalId = resolveBookmarkLocalId(remote.model, createIfMissing = false)
        if (collection != null && bookmarkLocalId != null) {
            bookmarkCollectionQueries.value.deleteRemoteBookmarkCollection(
                bookmark_local_id = bookmarkLocalId.toString(),
                collection_local_id = collection.local_id
            )
            return
        }
        bookmarkCollectionQueries.value.deleteRemoteBookmarkCollectionByRemoteId(remote_id = remote.remoteID)
    }

    private fun resolveBookmarkLocalId(
        bookmark: RemoteCollectionBookmark,
        createIfMissing: Boolean
    ): Long? {
        return when (bookmark) {
            is RemoteCollectionBookmark.Page -> {
                val existing = pageBookmarkQueries.value
                    .getBookmarkForPage(bookmark.page.toLong())
                    .executeAsOneOrNull()
                existing?.local_id
                    ?: if (createIfMissing) {
                        pageBookmarkQueries.value.addNewBookmark(bookmark.page.toLong())
                        pageBookmarkQueries.value.getBookmarkForPage(bookmark.page.toLong())
                            .executeAsOneOrNull()
                            ?.local_id
                    } else {
                        null
                    }
            }
            is RemoteCollectionBookmark.Ayah -> {
                val existing = ayahBookmarkQueries.value
                    .getBookmarkForAyah(bookmark.sura.toLong(), bookmark.ayah.toLong())
                    .executeAsOneOrNull()
                existing?.local_id
                    ?: if (createIfMissing) {
                        val ayahId = getAyahId(bookmark.sura, bookmark.ayah)
                        ayahBookmarkQueries.value.addNewBookmark(
                            ayah_id = ayahId.toLong(),
                            sura = bookmark.sura.toLong(),
                            ayah = bookmark.ayah.toLong()
                        )
                        ayahBookmarkQueries.value.getBookmarkForAyah(bookmark.sura.toLong(), bookmark.ayah.toLong())
                            .executeAsOneOrNull()
                            ?.local_id
                    } else {
                        null
                    }
            }
        }
    }

    private fun resolveBookmark(record: DatabaseBookmarkCollection): Bookmark? {
        val bookmarkLocalId = record.bookmark_local_id.toLongOrNull()
        if (bookmarkLocalId == null) {
            logger.w { "Skipping collection bookmark with non-numeric bookmark id: ${record.bookmark_local_id}" }
            return null
        }
        return when (record.bookmark_type.uppercase()) {
            "PAGE" -> {
                val recordBookmark = pageBookmarkQueries.value
                    .getBookmarkByLocalId(bookmarkLocalId)
                    .executeAsOneOrNull()
                recordBookmark?.toBookmark()
            }
            "AYAH" -> {
                val recordBookmark = ayahBookmarkQueries.value
                    .getBookmarkByLocalId(bookmarkLocalId)
                    .executeAsOneOrNull()
                recordBookmark?.toBookmark()
            }
            else -> null
        }
    }

    private fun DatabaseBookmarkCollection.toCollectionBookmark(
        collectionRemoteId: String?,
        bookmark: Bookmark
    ): CollectionBookmark {
        val updatedAt = Instant.fromEpochMilliseconds(modified_at).toPlatform()
        return when (bookmark) {
            is Bookmark.PageBookmark ->
                CollectionBookmark.PageBookmark(
                    collectionLocalId = collection_local_id.toString(),
                    collectionRemoteId = collectionRemoteId,
                    bookmarkLocalId = bookmark.localId,
                    page = bookmark.page,
                    lastUpdated = updatedAt,
                    localId = local_id.toString()
                )
            is Bookmark.AyahBookmark ->
                CollectionBookmark.AyahBookmark(
                    collectionLocalId = collection_local_id.toString(),
                    collectionRemoteId = collectionRemoteId,
                    bookmarkLocalId = bookmark.localId,
                    sura = bookmark.sura,
                    ayah = bookmark.ayah,
                    lastUpdated = updatedAt,
                    localId = local_id.toString()
                )
        }
    }

    private fun RemoteCollectionBookmark.toBookmarkTypeWithTimestamp(): Pair<String, Long> {
        val updatedAt = lastUpdated.fromPlatform().toEpochMilliseconds()
        return when (this) {
            is RemoteCollectionBookmark.Page -> "PAGE" to updatedAt
            is RemoteCollectionBookmark.Ayah -> "AYAH" to updatedAt
        }
    }

    private fun Bookmark.toCollectionBookmarkType(): String {
        return when (this) {
            is Bookmark.PageBookmark -> "PAGE"
            is Bookmark.AyahBookmark -> "AYAH"
        }
    }

    private fun getAyahId(sura: Int, ayah: Int): Int {
        // TODO - fix this
        return 1
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> {
        if (remoteIDs.isEmpty()) {
            return emptyMap()
        }

        return withContext(Dispatchers.IO) {
            val existentIDs = bookmarkCollectionQueries.value
                .checkRemoteIDsExistence(remoteIDs)
                .executeAsList()
                .map { it.remote_id }
                .toSet()

            remoteIDs.map { Pair(it, existentIDs.contains(it)) }
                .associateBy { it.first }
                .mapValues { it.value.second }
        }
    }
}
