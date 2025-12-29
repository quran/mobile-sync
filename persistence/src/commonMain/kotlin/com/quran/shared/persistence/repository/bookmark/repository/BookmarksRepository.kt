package com.quran.shared.persistence.repository.bookmark.repository

import com.quran.shared.persistence.model.Bookmark
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import kotlinx.coroutines.flow.Flow

interface BookmarksRepository {
    /**
     * Returns a Flow of all bookmarks, reflecting the latest state of the data.
     * The Flow will emit new values whenever the underlying data changes.
     *
     * @return Flow<List<Bookmark>> A flow that emits the current list of bookmarks
     */
    @NativeCoroutines
    fun getAllBookmarks(): Flow<List<Bookmark>>

    /**
     * Adds a bookmark for a specific page.
     */
    @NativeCoroutines
    suspend fun addPageBookmark(page: Int)

    /**
     * Deletes a bookmark for a specific page.
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
    suspend fun migrateBookmarks(bookmarks: List<Bookmark>)
}