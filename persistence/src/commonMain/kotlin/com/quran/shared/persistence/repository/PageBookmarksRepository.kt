package com.quran.shared.persistence.repository

import com.quran.shared.persistence.model.PageBookmarkMutation
import com.quran.shared.persistence.model.PageBookmark
import kotlinx.coroutines.flow.Flow

class DuplicatePageBookmarkException(message: String) : Exception(message)
class PageBookmarkNotFoundException(message: String) : Exception(message)

interface PageBookmarksSynchronizationRepository {
    /**
     * Returns a list of bookmarks that have been mutated locally (created or deleted)
     * and need to be synchronized with the remote server.
     */
    suspend fun fetchMutatedBookmarks(): List<PageBookmarkMutation>

    /**
     * Persists the remote state of bookmarks after a successful synchronization operation.
     * This method should be called after the remote server has confirmed the changes.
     *
     * @param updatesToPersist List of bookmarks with their remote IDs and mutation states to be
     * persisted.
     * @param localMutationsToClear List of local mutations to be cleared. An item of this list
     * denotes either a mutation that was committed remotely, or a mutation that overridden. If it
     * was committed, a counterpart is expected in `updatesToPersists` to persist it as a remote
     * bookmark.
     */
    suspend fun applyRemoteChanges(updatesToPersist: List<PageBookmarkMutation>,
                                   localMutationsToClear: List<PageBookmarkMutation>)
}

interface PageBookmarksRepository {
    /**
     * Returns a Flow of all page bookmarks, reflecting the latest state of the data.
     * The Flow will emit new values whenever the underlying data changes.
     *
     * @return Flow<List<PageBookmark>> A flow that emits the current list of bookmarks
     */
    fun getAllBookmarks(): Flow<List<PageBookmark>>

    /**
     * Adds a bookmark for a specific page.
     * @throws DuplicatePageBookmarkException if a bookmark for this page already exists
     */
    suspend fun addPageBookmark(page: Int)

    /**
     * Deletes a bookmark for a specific page.
     * @throws PageBookmarkNotFoundException if no bookmark exists for this page
     */
    suspend fun deletePageBookmark(page: Int)

    /**
     * Migrates existing bookmarks to the new storage format.
     * This method should only be called once during app initialization, after
     * bookmarks are added and before any changes by the user are handled.
     *
     * @param bookmarks List of page bookmarks to migrate
     * @throws IllegalStateException if either bookmarks or mutations tables are not empty
     * @throws IllegalArgumentException if any bookmark has a remote ID or is marked as deleted
     */
    suspend fun migrateBookmarks(bookmarks: List<PageBookmark>)
}