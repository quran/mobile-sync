package com.quran.shared.persistence.repository.bookmark.repository

import app.cash.sqldelight.coroutines.asFlow
import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.repository.bookmark.extension.toBookmark
import com.quran.shared.persistence.repository.bookmark.extension.toBookmarkMutation
import com.quran.shared.persistence.util.fromPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class BookmarksRepositoryImpl(
    private val database: QuranDatabase
) : BookmarksRepository, BookmarksSynchronizationRepository {

    private val logger = Logger.withTag("PageBookmarksRepository")
    private val pageBookmarkQueries = lazy { database.page_bookmarksQueries }

    override fun getAllBookmarks(): Flow<List<Bookmark>> {
        return pageBookmarkQueries.value.getBookmarks()
            .asFlow()
            .map { query ->
                query.executeAsList().map { it.toBookmark() }
            }
    }

    override suspend fun addPageBookmark(page: Int) {
        logger.i { "Adding page bookmark for page $page" }
        withContext(Dispatchers.IO) {
            pageBookmarkQueries.value.addNewBookmark(page.toLong())
        }
    }

    override suspend fun deletePageBookmark(page: Int) {
        logger.i { "Deleting page bookmark for page $page" }
        withContext(Dispatchers.IO) {
            pageBookmarkQueries.value.deleteBookmark(page.toLong())
        }
    }

    override suspend fun migrateBookmarks(bookmarks: List<Bookmark>) {
        withContext(Dispatchers.IO) {
            // Check if the bookmarks table is empty
            val existingBookmarks = pageBookmarkQueries.value.getBookmarks().executeAsList()
            if (existingBookmarks.isNotEmpty()) {
                throw IllegalStateException("Cannot migrate bookmarks: bookmarks table is not empty. Found ${existingBookmarks.size} bookmarks.")
            }

            database.transaction {
                bookmarks.forEach { bookmark ->
                    when (bookmark) {
                        is Bookmark.AyahBookmark -> TODO()
                        is Bookmark.PageBookmark ->
                            pageBookmarkQueries.value.addNewBookmark(bookmark.page.toLong())
                    }
                }
            }
        }
    }

    override suspend fun fetchMutatedBookmarks(): List<LocalModelMutation<Bookmark>> {
        return withContext(Dispatchers.IO) {
            pageBookmarkQueries.value.getUnsyncedBookmarks()
                .executeAsList()
                .map { it.toBookmarkMutation() }
        }
    }

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<Bookmark>>,
        localMutationsToClear: List<LocalModelMutation<Bookmark>>
    ) {
        logger.i { "Applying remote changes with ${updatesToPersist.size} updates to persist and ${localMutationsToClear.size} local mutations to clear" }
        return withContext(Dispatchers.IO) {
            database.transaction {
                // Clear local mutations
                // TODO: Should check that passed local IDs are valid
                localMutationsToClear.forEach { local ->
                    when (local.model) {
                        is Bookmark.AyahBookmark -> TODO()
                        is Bookmark.PageBookmark ->
                            pageBookmarkQueries.value.clearLocalMutationFor(id = local.localID.toLong())
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

    private fun applyRemoteBookmarkAddition(remote: RemoteModelMutation<Bookmark>) {
        when (val model = remote.model) {
            is Bookmark.AyahBookmark -> TODO()
            is Bookmark.PageBookmark ->
                pageBookmarkQueries.value.persistRemoteBookmark(
                    remote_id = remote.remoteID,
                    page = model.page.toLong(),
                    created_at = model.lastUpdated.fromPlatform().epochSeconds
                )
        }
    }

    private fun applyRemoteBookmarkDeletion(remote: RemoteModelMutation<Bookmark>) {
        when (remote.model) {
            is Bookmark.AyahBookmark -> TODO()
            is Bookmark.PageBookmark ->
                pageBookmarkQueries.value.hardDeleteBookmarkFor(remoteID = remote.remoteID)
        }
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> {
        if (remoteIDs.isEmpty()) {
            return emptyMap()
        }

        return withContext(Dispatchers.IO) {
            val existentIDs = pageBookmarkQueries.value.checkRemoteIDsExistence(remoteIDs)
                .executeAsList()
                .map { it.remote_id }
                .toSet()

            remoteIDs.map { Pair(it, existentIDs.contains(it)) }
                .associateBy { it.first }
                .mapValues { it.value.second }
        }
    }
}