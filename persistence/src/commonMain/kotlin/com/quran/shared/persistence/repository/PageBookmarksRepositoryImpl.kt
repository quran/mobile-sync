package com.quran.shared.persistence.repository

import app.cash.sqldelight.coroutines.asFlow
import co.touchlab.kermit.Logger
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.model.PageBookmark
import com.quran.shared.persistence.model.PageBookmarkMutation
import com.quran.shared.persistence.model.PageBookmarkMutationType
import com.quran.shared.persistence.repository.toBookmark
import com.quran.shared.persistence.repository.toBookmarkMutation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

class PageBookmarksRepositoryImpl(
    private val database: QuranDatabase
) : PageBookmarksRepository, PageBookmarksSynchronizationRepository {
    private val logger = Logger.withTag("PageBookmarksRepository")

    override fun getAllBookmarks(): Flow<List<PageBookmark>> {
        return database.bookmarksQueries.getBookmarks()
            .asFlow()
            .map { query ->
                query.executeAsList().map { it.toBookmark() }
            }
    }

    override suspend fun addPageBookmark(page: Int) {
        logger.i { "Adding page bookmark for page $page" }
        addBookmark(page)
    }

    private suspend fun addBookmark(page: Int) {
        withContext(Dispatchers.IO) {
            val existingRecords = database.bookmarksQueries
                .getAllRecordsFor(page.toLong())
                .executeAsList()

            if (existingRecords.isNotEmpty() && existingRecords[0].deleted == 0L) {
                logger.e { "Duplicate bookmark found for page=$page" }
                throw DuplicatePageBookmarkException("A bookmark already exists for page #$page")
            }
            else if (existingRecords.isNotEmpty() && existingRecords[0].deleted == 1L) {
                logger.d { "Restoring deleted bookmark for page=$page" }
                database.bookmarksQueries.resetDeleted(existingRecords[0].local_id)
            }
            else {
                database.bookmarksQueries.createLocalBookmark(page.toLong())
                logger.d { "Successfully created bookmark for page=$page" }
            }
        }
    }

    override suspend fun deletePageBookmark(page: Int) {
        logger.i { "Deleting page bookmark for page $page" }
        delete(page = page)
    }

    private suspend fun delete(page: Int) {
        withContext(Dispatchers.IO) {
            val existingBookmarks = database.bookmarksQueries
                .getBookmarksFor(page.toLong())
                .executeAsList()

            if (existingBookmarks.isEmpty()) {
                logger.w { "Bookmark not found for deletion: page=$page" }
                throw PageBookmarkNotFoundException("There's no bookmark for page #$page")
            }

            database.bookmarksQueries.deleteBookmark(page.toLong())
        }
    }

    override suspend fun migrateBookmarks(bookmarks: List<PageBookmark>) {
        withContext(Dispatchers.IO) {
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

            database.bookmarksQueries.transaction {
                bookmarks.forEach { bookmark ->
                    database.bookmarksQueries.createLocalBookmark(bookmark.page.toLong())
                }
            }
        }
    }

    override suspend fun fetchMutatedBookmarks(): List<PageBookmarkMutation> {
        return withContext(Dispatchers.IO) {
            database.bookmarksQueries.getUnsyncedBookmarks()
                .executeAsList()
                .map { it.toBookmarkMutation() }
        }
    }

    override suspend fun setToSyncedState(updatesToPersist: List<PageBookmarkMutation>) {
        logger.i { "Setting to synced state with ${updatesToPersist.size} updates to persist" }
        withContext(Dispatchers.IO) {
            database.bookmarksQueries.transaction {
                database.bookmarksQueries.clearLocalMutations()
                logger.d { "Cleared local mutations" }

                updatesToPersist.forEach { mutation ->
                    if (mutation.remoteId == null) {
                        logger.e { "Asked to persist a remote bookmark without a remote ID. Page: ${mutation.page}" }
                        throw IllegalArgumentException("Persisted remote bookmarks must have a remote ID. Details: ${mutation}")
                    }
                    when (mutation.mutationType) {
                        PageBookmarkMutationType.CREATED -> {
                            database.bookmarksQueries.createRemoteBookmark(
                                remote_id = mutation.remoteId,
                                page = mutation.page.toLong()
                            )
                        }
                        PageBookmarkMutationType.DELETED -> {
                            database.bookmarksQueries.deleteByRemoteID(mutation.remoteId)
                        }
                    }
                }
            }
        }
    }
}