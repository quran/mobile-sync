package com.quran.shared.persistence.repository

import com.quran.shared.persistence.model.PageBookmark
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import kotlinx.coroutines.flow.Flow

class DuplicatePageBookmarkException(message: String) : Exception(message)
class PageBookmarkNotFoundException(message: String) : Exception(message)

interface PageBookmarksRepository {
    /**
     * Returns a Flow of all page bookmarks, reflecting the latest state of the data.
     * The Flow will emit new values whenever the underlying data changes.
     *
     * @return Flow<List<PageBookmark>> A flow that emits the current list of bookmarks
     */
    @NativeCoroutines
    fun getAllBookmarks(): Flow<List<PageBookmark>>

    /**
     * Adds a bookmark for a specific page.
     * @throws DuplicatePageBookmarkException if a bookmark for this page already exists
     */
    @NativeCoroutines
    suspend fun addPageBookmark(page: Int)

    /**
     * Deletes a bookmark for a specific page.
     * @throws PageBookmarkNotFoundException if no bookmark exists for this page
     */
    @NativeCoroutines
    suspend fun deletePageBookmark(page: Int)

    /**
     * Migrates existing bookmarks to the new storage format.
     * This method should only be called once during app initialization, after
     * bookmarks are added and before any changes by the user are handled.
     *
     * @param bookmarks List of page bookmarks to migrate
     * @throws IllegalStateException if either bookmarks or mutations tables are not empty
     */
    @NativeCoroutines
    suspend fun migrateBookmarks(bookmarks: List<PageBookmark>)
}