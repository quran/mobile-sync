package com.quran.shared.persistence.repository

import app.cash.sqldelight.coroutines.asFlow
import co.touchlab.kermit.Logger
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.model.PageBookmark
import com.quran.shared.persistence.model.PageBookmarkMutation
import com.quran.shared.persistence.model.PageBookmarkMutationType
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
        withContext(Dispatchers.IO) {
            database.bookmarksQueries.addNewBookmark(page.toLong())
        }
    }

    override suspend fun deletePageBookmark(page: Int) {
        logger.i { "Deleting page bookmark for page $page" }
        withContext(Dispatchers.IO) {
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
                    database.bookmarksQueries.addNewBookmark(bookmark.page.toLong())
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

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<PageBookmarkMutation>,
        localMutationsToClear: List<PageBookmarkMutation>
    ) {
        logger.i { "Applying remote changes with ${updatesToPersist.size} updates to persist and ${localMutationsToClear.size} local mutations to clear" }
        return withContext(Dispatchers.IO) {
            database.bookmarksQueries.transaction {
                // Clear local mutations
                // TODO: Should check that passed local IDs are valid
                localMutationsToClear.forEach { local ->
                    if (local.localId == null) {
                        logger.e { "Local mutation without local ID: $local" }
                        throw IllegalArgumentException("Local mutations must have local ID")
                    }

                    database.bookmarksQueries.clearLocalMutationFor(id = local.localId)
                }
                
                // Apply remote updates
                updatesToPersist.forEach { remote ->
                    if (remote.remoteId == null) {
                        logger.e { "Remote mutation without remote ID: $remote" }
                        throw IllegalArgumentException("Remote mutations must have remote ID")
                    }
                    
                    when (remote.mutationType) {
                        PageBookmarkMutationType.CREATED -> {
                            database.bookmarksQueries.persistRemoteBookmark(
                                remote_id = remote.remoteId,
                                page = remote.page.toLong(),
                                created_at = remote.lastUpdated
                            )
                        }
                        PageBookmarkMutationType.DELETED -> {
                            database.bookmarksQueries.hardDeleteBookmarkFor(page = remote.page.toLong())
                        }
                    }
                }
            }
        }
    }
}