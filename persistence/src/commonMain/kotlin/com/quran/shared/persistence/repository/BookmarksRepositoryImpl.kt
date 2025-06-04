package com.quran.shared.persistence.repository

import app.cash.sqldelight.coroutines.asFlow
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.model.BookmarkMutation
import com.quran.shared.persistence.model.BookmarkMutationType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

class BookmarksRepositoryImpl(
    private val database: QuranDatabase
) : BookmarksRepository, BookmarksSynchronizationRepository {
    override fun getAllBookmarks(): Flow<List<Bookmark>> {
        val persistedFlow = database.bookmarksQueries.getBookmarks()
            .asFlow()
            .map { query ->
                query.executeAsList().map { it.toBookmark() }
            }
        val mutatedFlow = database.bookmarks_mutationsQueries.getBookmarksMutations()
            .asFlow()
            .map { query ->
                query.executeAsList().map { it.toBookmarkMutation() }
            }

        return merge(persistedFlow, mutatedFlow) { it.toBookmark() }
    }

    override fun getPageBookmarks(): Flow<List<Bookmark.PageBookmark>> {
        // sqldelight created different return types for these queries, so we couldn't
        // share the full logic with the other fetching methods.
        val persistedFlow = database.bookmarksQueries.getPageBookmarks()
            .asFlow()
            .map { query ->
                query.executeAsList().map { it.toPageBookmark() }
            }
        val mutatedFlow = database.bookmarks_mutationsQueries.getPageBookmarkMutations()
            .asFlow()
            .map { query ->
                query.executeAsList().map { it.toBookmarkMutation() }
            }

        return merge(persistedFlow, mutatedFlow) { it.toPageBookmark() }
    }

    override fun getAyahBookmarks(): Flow<List<Bookmark.AyahBookmark>> {
        val persistedFlow = database.bookmarksQueries.getAyahBookmarks()
            .asFlow()
            .map { query ->
                query.executeAsList().map { it.toAyahBookmark() }
            }
        val mutatedFlow = database.bookmarks_mutationsQueries.getAyahBookmarkMutations()
            .asFlow()
            .map { query ->
                query.executeAsList().map { it.toBookmarkMutation() }
            }

        return merge(persistedFlow, mutatedFlow) { it.toAyahBookmark() }
    }

    private fun <T: Bookmark>merge(
        persisted: Flow<List<T>>,
        mutated: Flow<List<BookmarkMutation>>,
        mapper: (BookmarkMutation) -> T?
    ): Flow<List<T>> = persisted.combine(mutated) { persistedBookmarks, mutatedBookmarks ->
        val deletedRemoteIDs = mutatedBookmarks
            .filter { it.remoteId != null } // TODO: This is confusing, but it's equivalent to the required result.
            .mapNotNull { it.remoteId }
            .toSet()

        persistedBookmarks.filter { it.remoteId !in deletedRemoteIDs } +
                mutatedBookmarks
                    .filter { it.mutationType == BookmarkMutationType.CREATED }
                    .mapNotNull(mapper)
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

    override suspend fun fetchMutatedBookmarks(): List<BookmarkMutation> {
        return withContext(Dispatchers.IO) {
            database.bookmarks_mutationsQueries.getBookmarksMutations()
                .executeAsList()
                .map { it.toBookmarkMutation() }
        }
    }

    override suspend fun clearLocalMutations() {
        withContext(Dispatchers.IO) {
            database.bookmarks_mutationsQueries.clearBookmarkMutations()
        }
    }

    override suspend fun persistRemoteUpdates(mutations: List<BookmarkMutation>) {
        withContext(Dispatchers.IO) {
            // Validate all bookmarks have remote IDs
            val invalidBookmarks = mutations.filter { it.remoteId == null }
            if (invalidBookmarks.isNotEmpty()) {
                throw IllegalArgumentException("All bookmarks must have a remote ID. Found ${invalidBookmarks.size} bookmarks without remote IDs.")
            }

            // Separate mutations by type
            val toDelete = mutations.filter { it.mutationType == BookmarkMutationType.DELETED }
            val toCreate = mutations.filter { it.mutationType == BookmarkMutationType.CREATED }

            database.bookmarksQueries.transaction {
                // Handle deletions first
                toDelete.forEach { mutation ->
                    database.bookmarksQueries.deleteBookmarkByRemoteId(mutation.remoteId!!)
                }

                // Handle creations
                toCreate.forEach { mutation ->
                    database.bookmarksQueries.addBookmark(
                        remote_id = mutation.remoteId!!,
                        sura = mutation.sura?.toLong(),
                        ayah = mutation.ayah?.toLong(),
                        page = mutation.page?.toLong(),
                        created_at = mutation.lastUpdated
                    )
                }
            }
        }
    }
}