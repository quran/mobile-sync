package com.quran.shared.persistence.repository

import app.cash.sqldelight.coroutines.asFlow
import co.touchlab.kermit.Logger
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.model.PageBookmark
import com.quran.shared.persistence.model.BookmarkMutation
import com.quran.shared.persistence.model.BookmarkMutationType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

class PageBookmarksRepositoryImpl(
    private val database: QuranDatabase
) : PageBookmarksRepository, BookmarksSynchronizationRepository {
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
            val existingBookmarks = database.bookmarksQueries
                .getBookmarksFor(page.toLong())
                .executeAsList()
            
            if (existingBookmarks.isNotEmpty()) {
                logger.e { "Duplicate bookmark found for page=$page" }
                throw DuplicateBookmarkException("A bookmark already exists for page #$page")
            }

            // TODO: Well, if we have a remote bookmark that is marked as deleted for the same
            // place as the input, how should we deal with that?
            database.bookmarksQueries.createLocalBookmark(page.toLong())
            logger.d { "Successfully created bookmark for page=$page" }
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
                throw BookmarkNotFoundException("There's no bookmark for page #$page")
            }

            val bookmark = existingBookmarks.first()
            if (bookmark.remote_id == null) {
                // Local-only bookmark: delete immediately
                logger.d { "Deleting local-only bookmark: page=$page" }
                database.bookmarksQueries.deleteBookmarkById(bookmark.local_id)
            } else {
                // Synced bookmark: mark as deleted
                logger.d { "Marking synced bookmark as deleted: page=$page" }
                database.bookmarksQueries.setDeleted(bookmark.local_id)
            }
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

    override suspend fun fetchMutatedBookmarks(): List<BookmarkMutation> {
        return withContext(Dispatchers.IO) {
            database.bookmarksQueries.getUnsyncedBookmarks()
                .executeAsList()
                .map { it.toBookmarkMutation() }
        }
    }

    override suspend fun setToSyncedState(updatesToPersist: List<BookmarkMutation>) {
        logger.i { "Setting to synced state with ${updatesToPersist.size} updates to persist" }
        withContext(Dispatchers.IO) {
            // Validate all bookmarks have remote IDs
            val invalidBookmarks = updatesToPersist.filter { it.remoteId == null }
            if (invalidBookmarks.isNotEmpty()) {
                logger.e { "Found ${invalidBookmarks.size} bookmarks without remote IDs" }
                throw IllegalArgumentException("All bookmarks must have a remote ID. Found ${invalidBookmarks.size} bookmarks without remote IDs.")
            }

            database.bookmarksQueries.transaction {
                database.bookmarksQueries.removeLocallyAddedBookmarks()
                database.bookmarksQueries.resetMarkedAsDeletedBookmarks()
                logger.d { "Cleared local mutations" }

                updatesToPersist.forEach { mutation ->
                    when (mutation.mutationType) {
                        BookmarkMutationType.CREATED -> {
                            database.bookmarksQueries.createRemoteBookmark(
                                remote_id = mutation.remoteId,
                                page = mutation.page.toLong()
                            )
                        }
                        BookmarkMutationType.DELETED -> {
                            val existingBookmark = database.bookmarksQueries.getBookmarkByRemoteId(mutation.remoteId!!).executeAsOneOrNull()
                            if (existingBookmark != null) {
                                database.bookmarksQueries.deleteBookmarkById(existingBookmark.local_id)
                            }
                        }
                    }
                }
            }
        }
    }
}