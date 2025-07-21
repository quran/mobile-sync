package com.quran.shared.persistence.repository

import app.cash.sqldelight.coroutines.asFlow
import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.model.PageBookmark
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

            database.bookmarksQueries.transaction {
                bookmarks.forEach { bookmark ->
                    database.bookmarksQueries.addNewBookmark(bookmark.page.toLong())
                }
            }
        }
    }

    override suspend fun fetchMutatedBookmarks(): List<LocalModelMutation<PageBookmark>> {
        return withContext(Dispatchers.IO) {
            database.bookmarksQueries.getUnsyncedBookmarks()
                .executeAsList()
                .map { it.toBookmarkMutation() }
        }
    }

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<PageBookmark>>,
        localMutationsToClear: List<LocalModelMutation<PageBookmark>>
    ) {
        logger.i { "Applying remote changes with ${updatesToPersist.size} updates to persist and ${localMutationsToClear.size} local mutations to clear" }
        return withContext(Dispatchers.IO) {
            database.bookmarksQueries.transaction {
                // Clear local mutations
                // TODO: Should check that passed local IDs are valid
                localMutationsToClear.forEach { local ->
                    database.bookmarksQueries.clearLocalMutationFor(id = local.localID.toLong())
                }
                
                // Apply remote updates
                updatesToPersist.forEach { remote ->
                    val model = remote.model
                    when (remote.mutation) {
                        Mutation.CREATED -> {
                            database.bookmarksQueries.persistRemoteBookmark(
                                remote_id = remote.remoteID,
                                page = model.page.toLong(),
                                created_at = model.lastUpdated
                            )
                        }
                        Mutation.DELETED -> {
                            database.bookmarksQueries.hardDeleteBookmarkFor(page = model.page.toLong())
                        }
                        Mutation.MODIFIED -> {
                            throw RuntimeException("Unexpected MODIFIED remote modification for page bookmarks.")
                        }
                    }
                }
            }
        }
    }
}