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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

class BookmarksRepositoryImpl(
    private val database: QuranDatabase
) : BookmarksRepository {
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
        addBookmark(page, null, null)
    }

    override suspend fun addAyahBookmark(sura: Int, ayah: Int) {
        addBookmark(null, sura, ayah)
    }

    private suspend fun addBookmark(page: Int?, sura: Int?, ayah: Int?) {
        withContext(Dispatchers.IO) {
            val mutatedBookmarks = database.bookmarks_mutationsQueries
                .getBookmarksMutationsFor(page?.toLong(), sura?.toLong(), ayah?.toLong())
                .executeAsList()
            if (mutatedBookmarks.isNotEmpty() && !mutatedBookmarks.none{ it.deleted == 0L }) {
                throw DuplicateBookmarkException("A bookmark already exists for page #$page or ayah #$ayah of sura #$sura")
            }
            val persistedBookmarks = database.bookmarksQueries
                .getBookmarksFor(page?.toLong(), sura?.toLong(), ayah?.toLong())
                .executeAsList()
            if (persistedBookmarks.isNotEmpty()) {
                throw DuplicateBookmarkException("A bookmark already exists for page #$page or ayah #$ayah of sura #$sura")
            }
            database.bookmarks_mutationsQueries.createBookmark(sura?.toLong(), ayah?.toLong(), page?.toLong(), null)
        }
    }

    override suspend fun deletePageBookmark(page: Int) {
        delete(page, null, null)
    }

    override suspend fun deleteAyahBookmark(sura: Int, ayah: Int) {
        delete(null, sura, ayah)
    }

    private suspend fun delete(page: Int?, sura: Int?, ayah: Int?) {
        withContext(Dispatchers.IO) {
            val persistedBookmarks = database.bookmarksQueries
                .getBookmarksFor(page?.toLong(), sura?.toLong(), ayah?.toLong())
                .executeAsList()
            val mutatedBookmarks = database.bookmarks_mutationsQueries
                .getBookmarksMutationsFor(page?.toLong(), sura?.toLong(), ayah?.toLong())
                .executeAsList()

            val deletedBookmark = mutatedBookmarks.firstOrNull{ it.deleted == 1L }
            val createdBookmark = mutatedBookmarks.firstOrNull{ it.deleted == 0L }
            val persistedBookmark = persistedBookmarks.firstOrNull()

            if (createdBookmark != null) {
                database.bookmarks_mutationsQueries.deleteBookmarkMutation(createdBookmark.local_id)
            }
            else if (deletedBookmark != null) {
                throw BookmarkNotFoundException("There's no bookmark page #$page or ayah #$ayah of sura #$sura")
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
                throw BookmarkNotFoundException("There's no bookmark page #$page or ayah #$ayah of sura #$sura")
            }
        }
    }

    override suspend fun migrateBookmarks(bookmarks: List<Bookmark>) {
        withContext(Dispatchers.IO) {
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
                    val (page, sura, ayah) = when (bookmark) {
                        is Bookmark.PageBookmark -> Triple(bookmark.page.toLong(), null, null)
                        is Bookmark.AyahBookmark -> Triple(null, bookmark.sura.toLong(), bookmark.ayah.toLong())
                    }
                    database.bookmarks_mutationsQueries.createBookmark(sura, ayah, page, null)
                }
            }
        }
    }

    override suspend fun fetchMutatedBookmarks(): List<Bookmark> {
        return withContext(Dispatchers.IO) {
            database.bookmarks_mutationsQueries.getBookmarksMutations()
                .executeAsList()
                .map { it.toBookmark() }
        }
    }

    override suspend fun clearLocalMutations() {
        withContext(Dispatchers.IO) {
            database.bookmarks_mutationsQueries.clearBookmarkMutations()
        }
    }

    override suspend fun persistRemoteUpdates(mutations: List<Bookmark>) {
        withContext(Dispatchers.IO) {
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
                    val (page, sura, ayah) = when (bookmark) {
                        is Bookmark.PageBookmark -> Triple(bookmark.page.toLong(), null, null)
                        is Bookmark.AyahBookmark -> Triple(null, bookmark.sura.toLong(), bookmark.ayah.toLong())
                    }
                    database.bookmarksQueries.addBookmark(
                        remote_id = bookmark.remoteId!!,
                        sura = sura,
                        ayah = ayah,
                        page = page,
                        created_at = bookmark.lastUpdated
                    )
                }
            }
        }
    }
}

private fun Bookmarks_mutations.toBookmark(): Bookmark {
    return if (page != null) {
        Bookmark.PageBookmark(
            page = page.toInt(),
            remoteId = remote_id,
            localMutation = if (deleted == 1L) BookmarkLocalMutation.DELETED else BookmarkLocalMutation.CREATED,
            lastUpdated = created_at
        )
    } else {
        Bookmark.AyahBookmark(
            sura = sura!!.toInt(),
            ayah = ayah!!.toInt(),
            remoteId = remote_id,
            localMutation = if (deleted == 1L) BookmarkLocalMutation.DELETED else BookmarkLocalMutation.CREATED,
            lastUpdated = created_at
        )
    }
}

private fun Bookmarks.toBookmark(): Bookmark {
    return if (page != null) {
        Bookmark.PageBookmark(
            page = page.toInt(),
            remoteId = remote_id,
            localMutation = BookmarkLocalMutation.NONE,
            lastUpdated = created_at
        )
    } else {
        Bookmark.AyahBookmark(
            sura = sura!!.toInt(),
            ayah = ayah!!.toInt(),
            remoteId = remote_id,
            localMutation = BookmarkLocalMutation.NONE,
            lastUpdated = created_at
        )
    }
}