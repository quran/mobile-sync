package com.quran.shared.persistence.repository.bookmark.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.touchlab.kermit.Logger
import com.quran.shared.di.AppScope
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.input.BookmarkMigration
import com.quran.shared.persistence.input.RemoteBookmark
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.repository.bookmark.extension.toBookmark
import com.quran.shared.persistence.repository.bookmark.extension.toBookmarkMutation
import com.quran.shared.persistence.util.QuranData
import com.quran.shared.persistence.util.SQLITE_MAX_BIND_PARAMETERS
import com.quran.shared.persistence.util.fromPlatform
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

@Inject
@SingleIn(AppScope::class)
class BookmarksRepositoryImpl(
    private val database: QuranDatabase
) : BookmarksRepository, BookmarksSynchronizationRepository {

    private val logger = Logger.withTag("PageBookmarksRepository")
    private val pageBookmarkQueries = lazy { database.page_bookmarksQueries }
    private val ayahBookmarkQueries = lazy { database.ayah_bookmarksQueries }

    override suspend fun getAllBookmarks(): List<Bookmark> {
        return withContext(Dispatchers.IO) {
            val pageBookmarks = pageBookmarkQueries.value.getBookmarks()
                .executeAsList()
                .map { it.toBookmark() }
            val ayahBookmarks = ayahBookmarkQueries.value.getBookmarks()
                .executeAsList()
                .map { it.toBookmark() }

            // TODO - sort options - ex sort by location, by date added (default)
            sortBookmarks(pageBookmarks + ayahBookmarks)
        }
    }

    override fun getBookmarksFlow(): Flow<List<Bookmark>> {
        val pageBookmarksFlow = pageBookmarkQueries.value.getBookmarks()
            .asFlow()
            .mapToList(Dispatchers.IO)

        val ayahBookmarksFlow = ayahBookmarkQueries.value.getBookmarks()
            .asFlow()
            .mapToList(Dispatchers.IO)

        return combine(pageBookmarksFlow, ayahBookmarksFlow) { pageList, ayahList ->
            val pageBookmarks = pageList.map { it.toBookmark() }
            val ayahBookmarks = ayahList.map { it.toBookmark() }
            sortBookmarks(pageBookmarks + ayahBookmarks)
        }
    }

    private fun sortBookmarks(bookmarks: List<Bookmark>): List<Bookmark> {
        return bookmarks.sortedByDescending { bookmark ->
            when (bookmark) {
                is Bookmark.AyahBookmark -> bookmark.lastUpdated.fromPlatform()
                    .toEpochMilliseconds()

                is Bookmark.PageBookmark -> bookmark.lastUpdated.fromPlatform()
                    .toEpochMilliseconds()
            }
        }
    }

    override suspend fun addBookmark(page: Int): Bookmark.PageBookmark {
        logger.i { "Adding page bookmark for page $page" }
        return withContext(Dispatchers.IO) {
            pageBookmarkQueries.value.addNewBookmark(page.toLong())
            val record = pageBookmarkQueries.value.getBookmarkForPage(page.toLong())
                .executeAsOneOrNull()
            requireNotNull(record) { "Expected page bookmark for page $page after insert." }
            record.toBookmark()
        }
    }

    override suspend fun addBookmark(sura: Int, ayah: Int): Bookmark.AyahBookmark {
        logger.i { "Adding ayah bookmark for $sura:$ayah" }
        return withContext(Dispatchers.IO) {
            val ayahId = getAyahId(sura, ayah)
            ayahBookmarkQueries.value.addNewBookmark(ayahId.toLong(), sura.toLong(), ayah.toLong())
            val record = ayahBookmarkQueries.value.getBookmarkForAyah(sura.toLong(), ayah.toLong())
                .executeAsOneOrNull()
            requireNotNull(record) { "Expected ayah bookmark for $sura:$ayah after insert." }
            record.toBookmark()
        }
    }

    override suspend fun deleteBookmark(page: Int): Boolean {
        logger.i { "Deleting page bookmark for page $page" }
        withContext(Dispatchers.IO) {
            pageBookmarkQueries.value.deleteBookmark(page.toLong())
        }
        return true
    }

    override suspend fun deleteBookmark(sura: Int, ayah: Int): Boolean {
        logger.i { "Deleting page bookmark for $sura:$ayah" }
        withContext(Dispatchers.IO) {
            ayahBookmarkQueries.value.deleteBookmark(sura.toLong(), ayah.toLong())
        }
        return true
    }

    override suspend fun migrateBookmarks(bookmarks: List<BookmarkMigration>) {
        withContext(Dispatchers.IO) {
            // Check if the bookmarks table is empty
            val existingBookmarks = pageBookmarkQueries.value.getBookmarks().executeAsList()
            if (existingBookmarks.isNotEmpty()) {
                throw IllegalStateException("Cannot migrate bookmarks: bookmarks table is not empty. Found ${existingBookmarks.size} bookmarks.")
            }

            database.transaction {
                bookmarks.forEach { bookmark ->
                    when (bookmark) {
                        is BookmarkMigration.Ayah -> {
                            val ayahId = getAyahId(bookmark.sura, bookmark.ayah)
                            ayahBookmarkQueries.value.addNewBookmark(
                                ayahId.toLong(),
                                bookmark.sura.toLong(),
                                bookmark.ayah.toLong()
                            )
                        }

                        is BookmarkMigration.Page ->
                            pageBookmarkQueries.value.addNewBookmark(bookmark.page.toLong())
                    }
                }
            }
        }
    }

    override suspend fun fetchMutatedBookmarks(): List<LocalModelMutation<Bookmark>> {
        return withContext(Dispatchers.IO) {
            val pageMutations = pageBookmarkQueries.value.getUnsyncedBookmarks()
                .executeAsList()
                .map { it.toBookmarkMutation() }
            val ayahMutations = ayahBookmarkQueries.value.getUnsyncedBookmarks()
                .executeAsList()
                .map { it.toBookmarkMutation() }
            pageMutations + ayahMutations
        }
    }

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteBookmark>>,
        localMutationsToClear: List<LocalModelMutation<Bookmark>>
    ) {
        logger.i { "Applying remote changes with ${updatesToPersist.size} updates to persist and ${localMutationsToClear.size} local mutations to clear" }
        return withContext(Dispatchers.IO) {
            database.transaction {
                val committedCreationKeys = updatesToPersist
                    .filter { it.mutation == Mutation.CREATED }
                    .map { it.model.key() }
                    .toSet()

                // Clear local mutations
                // TODO: Should check that passed local IDs are valid
                localMutationsToClear.forEach { local ->
                    when (local.mutation) {
                        Mutation.DELETED -> clearLocalMutation(local)
                        Mutation.CREATED, Mutation.MODIFIED -> {
                            val localKey = local.model.key()
                            if (!committedCreationKeys.contains(localKey)) {
                                clearLocalMutation(local)
                            }
                        }
                    }
                }

                // Apply remote updates
                updatesToPersist.forEach { remote ->
                    when (remote.mutation) {
                        Mutation.CREATED -> applyRemoteBookmarkAddition(remote)
                        Mutation.DELETED -> applyRemoteBookmarkDeletion(remote)
                        Mutation.MODIFIED -> {
                            throw RuntimeException("Unexpected MODIFIED remote modification for page bookmarks.")
                        }
                    }
                }
            }
        }
    }

    private fun applyRemoteBookmarkAddition(remote: RemoteModelMutation<RemoteBookmark>) {
        when (val model = remote.model) {
            is RemoteBookmark.Ayah -> {
                val ayahId = getAyahId(model.sura, model.ayah)
                val updatedAt = model.lastUpdated.fromPlatform().toEpochMilliseconds()
                ayahBookmarkQueries.value.persistRemoteBookmark(
                    remote_id = remote.remoteID,
                    ayah_id = ayahId.toLong(),
                    sura = model.sura.toLong(),
                    ayah = model.ayah.toLong(),
                    created_at = updatedAt,
                    modified_at = updatedAt
                )
            }

            is RemoteBookmark.Page ->
                pageBookmarkQueries.value.persistRemoteBookmark(
                    remote_id = remote.remoteID,
                    page = model.page.toLong(),
                    created_at = model.lastUpdated.fromPlatform().toEpochMilliseconds(),
                    modified_at = model.lastUpdated.fromPlatform().toEpochMilliseconds()
                )
        }
    }

    private fun clearLocalMutation(local: LocalModelMutation<Bookmark>) {
        when (local.model) {
            is Bookmark.AyahBookmark ->
                ayahBookmarkQueries.value.clearLocalMutationFor(id = local.localID.toLong())

            is Bookmark.PageBookmark ->
                pageBookmarkQueries.value.clearLocalMutationFor(id = local.localID.toLong())
        }
    }

    private fun applyRemoteBookmarkDeletion(remote: RemoteModelMutation<RemoteBookmark>) {
        when (remote.model) {
            is RemoteBookmark.Ayah ->
                ayahBookmarkQueries.value.hardDeleteBookmarkFor(remoteID = remote.remoteID)

            is RemoteBookmark.Page ->
                pageBookmarkQueries.value.hardDeleteBookmarkFor(remoteID = remote.remoteID)
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
                    pageBookmarkQueries.value.checkRemoteIDsExistence(chunk)
                        .executeAsList()
                        .mapNotNull { it.remote_id }
                )
                existentIDs.addAll(
                    ayahBookmarkQueries.value.checkRemoteIDsExistence(chunk)
                        .executeAsList()
                        .mapNotNull { it.remote_id }
                )
            }

            remoteIDs.associateWith { existentIDs.contains(it) }
        }
    }

    override suspend fun fetchBookmarkByRemoteId(remoteId: String): Bookmark? {
        return withContext(Dispatchers.IO) {
            val pageBookmark = pageBookmarkQueries.value.getBookmarkByRemoteId(remoteId)
                .executeAsOneOrNull()
            if (pageBookmark != null) {
                return@withContext pageBookmark.toBookmark()
            }

            val ayahBookmark = ayahBookmarkQueries.value.getBookmarkByRemoteId(remoteId)
                .executeAsOneOrNull()
            ayahBookmark?.toBookmark()
        }
    }
}

private data class BookmarkKey(val type: String, val first: Int, val second: Int?)

private fun Bookmark.key(): BookmarkKey {
    return when (this) {
        is Bookmark.PageBookmark -> BookmarkKey("PAGE", page, null)
        is Bookmark.AyahBookmark -> BookmarkKey("AYAH", sura, ayah)
    }
}

private fun RemoteBookmark.key(): BookmarkKey {
    return when (this) {
        is RemoteBookmark.Page -> BookmarkKey("PAGE", page, null)
        is RemoteBookmark.Ayah -> BookmarkKey("AYAH", sura, ayah)
    }
}
