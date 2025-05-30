package com.quran.shared.persistence.repository

import app.cash.sqldelight.coroutines.asFlow
import com.quran.shared.persistence.Bookmarks
import com.quran.shared.persistence.Bookmarks_mutations
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.model.BookmarkLocalMutation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class BookmarksRepositoryImpl(
    private val database: QuranDatabase
) : BookmarkRepository {
    override fun getAllBookmarks(): Flow<List<Bookmark>> {
        val persistedFlow = database.bookmarksQueries.getBookmarks()
            .asFlow()
            .map { query ->
                query.executeAsList().map { it.toBookmark() }
            }
        val mutatedFlow = database.bookmarks_mutationsQueries.getBookmarksMutations()
            .asFlow()
            .map { query ->
                query.executeAsList().map { it.toBookmark() }
            }

        return persistedFlow.combine(mutatedFlow) { persistedBookmarks, mutatedBookmarks ->
            val deletedRemoteIDs = mutatedBookmarks
                .filter { it.localMutation == BookmarkLocalMutation.DELETED }
                .mapNotNull { it.remoteId }
                .toSet()

            persistedBookmarks.filter { it.remoteId !in deletedRemoteIDs } +
                    mutatedBookmarks.filter { it.remoteId !in deletedRemoteIDs }
        }
    }

    override suspend fun addPageBookmark(page: Int) {
        val bookmarks = database.bookmarks_mutationsQueries.recordsForPage(page.toLong())
            .executeAsList()
        if (bookmarks.isNotEmpty() && !bookmarks.none { it.deleted == 0L }) {
            throw DuplicateBookmarkException("A bookmark already exists for page $page")
        }
        val persistedBookmarks = database.bookmarksQueries.getBookmarksForPage(page.toLong())
            .executeAsList()
        if (persistedBookmarks.isNotEmpty()) {
            // TODO: It remains to check if that is deleted.
            throw DuplicateBookmarkException("A bookmark already exists for page $page")
        }
        database.bookmarks_mutationsQueries.createBookmark(null, null, page.toLong(), null)
    }

    override suspend fun addAyahBookmark(sura: Int, ayah: Int) {
        val bookmarks = database.bookmarks_mutationsQueries.recordsForAyah(sura.toLong(), ayah.toLong())
            .executeAsList()
        if (bookmarks.isNotEmpty() && !bookmarks.none{ it.deleted == 0L }) {
            throw DuplicateBookmarkException("A bookmark already exists for ayah #$ayah of sura #$sura")
        }
        val persistedBookmarks = database.bookmarksQueries.getBookmarksForAyah(
            sura = sura.toLong(),
            ayah = ayah.toLong()
        ).executeAsList()
        if (persistedBookmarks.isNotEmpty()) {
            // TODO: It remains to check if that is deleted.
            throw DuplicateBookmarkException("A bookmark already exists for ayah #$ayah of sura #$sura")
        }
        database.bookmarks_mutationsQueries.createBookmark(sura.toLong(), ayah.toLong(), null, null)
    }

    override suspend fun deletePageBookmark(page: Int) {
        val persistedBookmarks = database.bookmarksQueries.getBookmarksForPage(page.toLong())
            .executeAsList()
        val mutatedBookmarks = database.bookmarks_mutationsQueries.recordsForPage(page.toLong())
            .executeAsList()

        delete(persistedBookmarks, mutatedBookmarks, "on page $page")
    }

    override suspend fun deleteAyahBookmark(sura: Int, ayah: Int) {
        val persistedBookmarks = database.bookmarksQueries.getBookmarksForAyah(sura.toLong(), ayah.toLong())
            .executeAsList()
        val mutatedBookmarks = database.bookmarks_mutationsQueries.recordsForAyah(sura.toLong(), ayah.toLong())
            .executeAsList()

        delete(persistedBookmarks, mutatedBookmarks, "on ayah $ayah of sura $sura")
    }

    private fun delete(persisted: List<Bookmarks>, mutated: List<Bookmarks_mutations>, description: String) {
        val deletedBookmark = mutated.firstOrNull{ it.deleted == 1L }
        val createdBookmark = mutated.firstOrNull{ it.deleted == 0L }
        val persistedBookmark = persisted.firstOrNull()

        if (createdBookmark != null) {
            database.bookmarks_mutationsQueries.deleteBookmarkMutation(createdBookmark.local_id)
        }
        else if (deletedBookmark != null) {
            throw BookmarkNotFoundException("There's no bookmark $description")
        }
        else if (persistedBookmark != null) {
            database.bookmarks_mutationsQueries.createMarkAsDeletedRecord(
                persistedBookmark.sura,
                persistedBookmark.ayah,
                persistedBookmark.page,
                persistedBookmark.remote_id
            )
        }
        else {
            throw BookmarkNotFoundException("There's no bookmark $description")
        }
    }

    override suspend fun migrateBookmarks(bookmarks: List<Bookmark>) {
        // Check if mutations table is empty
        val existingMutations = database.bookmarks_mutationsQueries.getBookmarksMutations().executeAsList()
        if (existingMutations.isNotEmpty()) {
            throw IllegalStateException("Cannot migrate bookmarks: mutations table is not empty. Found ${existingMutations.size} mutations.")
        }

        // Check if bookmarks table is empty
        val existingBookmarks = database.bookmarksQueries.getBookmarks().executeAsList()
        if (existingBookmarks.isNotEmpty()) {
            throw IllegalStateException("Cannot migrate bookmarks: bookmarks table is not empty. Found ${existingBookmarks.size} bookmarks.")
        }

        // Validate that all bookmarks are from the old system (no remote IDs)
        val bookmarksWithRemoteId = bookmarks.filter { it.remoteId != null }
        if (bookmarksWithRemoteId.isNotEmpty()) {
            throw IllegalArgumentException("Cannot migrate bookmarks with remote IDs. Found ${bookmarksWithRemoteId.size} bookmarks with remote IDs.")
        }

        val deletedBookmarks = bookmarks.filter { it.localMutation == BookmarkLocalMutation.DELETED }
        if (deletedBookmarks.isNotEmpty()) {
            throw IllegalArgumentException("Cannot migrate deleted bookmarks. Found ${deletedBookmarks.size} bookmarks marked as deleted.")
        }

        database.bookmarks_mutationsQueries.transaction {
            bookmarks.forEach { bookmark ->
                database.bookmarks_mutationsQueries.createBookmark(
                    sura = bookmark.sura?.toLong(),
                    ayah = bookmark.ayah?.toLong(),
                    page = bookmark.page?.toLong(),
                    remote_id = null
                )
            }
        }
    }

    override suspend fun fetchMutatedBookmarks(): List<Bookmark> {
        return database.bookmarks_mutationsQueries.getBookmarksMutations()
            .executeAsList()
            .map { it.toBookmark() }
    }

    override suspend fun clearLocalMutations() {
        database.bookmarks_mutationsQueries.clearBookmarkMutations()
    }

    override suspend fun persistRemoteUpdates(mutations: List<Bookmark>) {
        // Validate all bookmarks have remote IDs
        val invalidBookmarks = mutations.filter { it.remoteId == null }
        if (invalidBookmarks.isNotEmpty()) {
            throw IllegalArgumentException("All bookmarks must have a remote ID. Found ${invalidBookmarks.size} bookmarks without remote IDs.")
        }

        // Separate mutations by type
        val toDelete = mutations.filter { it.localMutation == BookmarkLocalMutation.DELETED }
        val toCreate = mutations.filter { it.localMutation == BookmarkLocalMutation.CREATED }

        // TODO: Guard against inputted bookmarks with NONE as a mutation. 

        database.bookmarksQueries.transaction {
            // Handle deletions first
            toDelete.forEach { bookmark ->
                database.bookmarksQueries.deleteBookmarkByRemoteId(bookmark.remoteId!!)
            }

            // Handle creations
            toCreate.forEach { bookmark ->
                database.bookmarksQueries.addBookmark(
                    remote_id = bookmark.remoteId!!,
                    sura = bookmark.sura?.toLong(),
                    ayah = bookmark.ayah?.toLong(),
                    page = bookmark.page?.toLong(),
                    created_at = bookmark.lastUpdated
                )
            }
        }
    }
}

private fun Bookmarks_mutations.toBookmark(): Bookmark = Bookmark(
    sura = sura?.toInt(),
    ayah = ayah?.toInt(),
    page = page?.toInt(),
    remoteId = remote_id,
    localMutation = if (deleted == 1L) BookmarkLocalMutation.DELETED else BookmarkLocalMutation.CREATED,
    lastUpdated = created_at
)

private fun Bookmarks.toBookmark(): Bookmark = Bookmark(
    sura = sura?.toInt(),
    ayah = ayah?.toInt(),
    page = page?.toInt(),
    remoteId = remote_id,
    localMutation = BookmarkLocalMutation.NONE,
    lastUpdated = created_at
)