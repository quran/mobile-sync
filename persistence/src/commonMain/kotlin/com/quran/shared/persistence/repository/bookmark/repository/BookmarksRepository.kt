package com.quran.shared.persistence.repository.bookmark.repository

import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.input.BookmarkMigration
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import kotlinx.coroutines.flow.Flow

interface BookmarksRepository {
    /**
     * Fetch and returns all bookmarks.
     *
     * @return List<Bookmark> the current list of bookmarks
     */
    @NativeCoroutines
    suspend fun getAllBookmarks(): List<Bookmark>

    /**
     * Add a bookmark for a specific page.
     *
     * @return the [Bookmark.PageBookmark]
     */
    @NativeCoroutines
    suspend fun addBookmark(page: Int): Bookmark.PageBookmark

    /**
     * Add a bookmark for a given sura and ayah.
     *
     * @return the [Bookmark.AyahBookmark]
     */
    @NativeCoroutines
    suspend fun addBookmark(sura: Int, ayah: Int): Bookmark

    /**
     * Delete a bookmark for a specific page.
     *
     * @return a boolean denoting success
     */
    @NativeCoroutines
    suspend fun deleteBookmark(page: Int): Boolean

    /**
     * Delete a bookmark for a sura and ayah.
     *
     * @return a boolean denoting success
     */
    @NativeCoroutines
    suspend fun deleteBookmark(sura: Int, ayah: Int): Boolean

    /**
     * Migrates existing bookmarks to the new storage format.
     * This method should only be called once during app initialization, after
     * bookmarks are added and before any changes by the user are handled.
     *
     * @param bookmarks List of bookmarks to migrate
     * @throws IllegalStateException if either bookmarks or mutations tables are not empty
     */
    @NativeCoroutines
    suspend fun migrateBookmarks(bookmarks: List<BookmarkMigration>)
}
