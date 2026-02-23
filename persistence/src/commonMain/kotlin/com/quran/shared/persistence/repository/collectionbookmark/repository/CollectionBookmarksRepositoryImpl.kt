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
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.model.CollectionBookmark
import com.quran.shared.persistence.model.DatabaseBookmarkCollection
import com.quran.shared.persistence.util.QuranData
import com.quran.shared.persistence.util.SQLITE_MAX_BIND_PARAMETERS
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toPlatform
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@SingleIn(AppScope::class)
class CollectionBookmarksRepositoryImpl @Inject constructor(
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
                .getCollectionBookmarksForCollectionWithDetails(collection_local_id = collectionLocalId.toLong())
                .executeAsList()
                .mapNotNull { record ->
                    toCollectionBookmark(
                        bookmarkType = record.bookmark_type,
                        bookmarkLocalId = record.bookmark_local_id,
                        page = record.page,
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

    override fun getBookmarksForCollectionFlow(collectionLocalId: String): Flow<List<CollectionBookmark>> {
        return bookmarkCollectionQueries.value
            .getCollectionBookmarksForCollectionWithDetails(collection_local_id = collectionLocalId.toLong())
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list ->
                list.mapNotNull { record ->
                    toCollectionBookmark(
                        bookmarkType = record.bookmark_type,
                        bookmarkLocalId = record.bookmark_local_id,
                        page = record.page,
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

    override suspend fun addBookmarkToCollection(
        collectionLocalId: String,
        bookmark: Bookmark
    ): CollectionBookmark {
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

    override suspend fun removeBookmarkFromCollection(
        collectionLocalId: String,
        bookmark: Bookmark
    ): Boolean {
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
            bookmarkCollectionQueries.value.getUnsyncedCollectionBookmarksWithDetails()
                .executeAsList()
                .mapNotNull { record ->
                    val collectionRemoteId = record.collection_remote_id
                    if (collectionRemoteId.isNullOrEmpty()) {
                        logger.w { "Skipping collection bookmark without remote collection ID: localId=${record.local_id}" }
                        return@mapNotNull null
                    }
                    val mutation = if (record.deleted == 1L) Mutation.DELETED else Mutation.CREATED
                    val collectionBookmark = toCollectionBookmark(
                        bookmarkType = record.bookmark_type,
                        bookmarkLocalId = record.bookmark_local_id,
                        page = record.page,
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
                val page = bookmark.page.toLong()
                if (createIfMissing) {
                    pageBookmarkQueries.value.insertBookmarkIfMissing(page)
                }
                pageBookmarkQueries.value.getBookmarkForPage(page)
                    .executeAsOneOrNull()
                    ?.local_id
            }

            is RemoteCollectionBookmark.Ayah -> {
                val sura = bookmark.sura.toLong()
                val ayah = bookmark.ayah.toLong()
                if (createIfMissing) {
                    val ayahId = getAyahId(bookmark.sura, bookmark.ayah)
                    ayahBookmarkQueries.value.insertBookmarkIfMissing(
                        ayah_id = ayahId.toLong(),
                        sura = sura,
                        ayah = ayah
                    )
                }
                ayahBookmarkQueries.value.getBookmarkForAyah(sura, ayah)
                    .executeAsOneOrNull()
                    ?.local_id
            }
        }
    }

    private fun toCollectionBookmark(
        bookmarkType: String,
        bookmarkLocalId: String,
        page: Long?,
        sura: Long?,
        ayah: Long?,
        collectionLocalId: Long,
        collectionRemoteId: String?,
        modifiedAt: Long,
        localId: Long,
        logMissingBookmark: Boolean
    ): CollectionBookmark? {
        if (bookmarkLocalId.toLongOrNull() == null) {
            logger.w { "Skipping collection bookmark with non-numeric bookmark id: $bookmarkLocalId" }
            return null
        }
        val updatedAt = Instant.fromEpochMilliseconds(modifiedAt).toPlatform()
        return when (bookmarkType.uppercase()) {
            "PAGE" -> {
                val pageValue = page?.toInt()
                if (pageValue == null) {
                    if (logMissingBookmark) {
                        logger.w { "Skipping collection bookmark without local bookmark: localId=$localId" }
                    }
                    null
                } else {
                    CollectionBookmark.PageBookmark(
                        collectionLocalId = collectionLocalId.toString(),
                        collectionRemoteId = collectionRemoteId,
                        bookmarkLocalId = bookmarkLocalId,
                        page = pageValue,
                        lastUpdated = updatedAt,
                        localId = localId.toString()
                    )
                }
            }

            "AYAH" -> {
                val suraValue = sura?.toInt()
                val ayahValue = ayah?.toInt()
                if (suraValue == null || ayahValue == null) {
                    if (logMissingBookmark) {
                        logger.w { "Skipping collection bookmark without local bookmark: localId=$localId" }
                    }
                    null
                } else {
                    CollectionBookmark.AyahBookmark(
                        collectionLocalId = collectionLocalId.toString(),
                        collectionRemoteId = collectionRemoteId,
                        bookmarkLocalId = bookmarkLocalId,
                        sura = suraValue,
                        ayah = ayahValue,
                        lastUpdated = updatedAt,
                        localId = localId.toString()
                    )
                }
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
        return QuranData.getAyahId(sura, ayah)
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
            }

            remoteIDs.associateWith { existentIDs.contains(it) }
        }
    }
}
