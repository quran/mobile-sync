package com.quran.shared.persistence.repository.bookmark.ayah

import com.quran.shared.persistence.model.AyahBookmark
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import kotlinx.coroutines.flow.Flow

class DuplicateAyahBookmarkException(message: String) : Exception(message)

interface AyahBookmarksRepository {
    /**
     * Returns a Flow of all ayah bookmarks, reflecting the latest state of the data.
     * The Flow will emit new values whenever the underlying data changes.
     *
     * @return Flow<List<AyahBookmark>> A flow that emits the current list of bookmarks
     */
    @NativeCoroutines
    fun getAllBookmarks(): Flow<List<AyahBookmark>>

    /**
     * Adds a bookmark for a specific ayah.
     * @throws DuplicateAyahBookmarkException if a bookmark for this page already exists
     */
    @NativeCoroutines
    suspend fun addAyahBookmark(sura: Int, ayah: Int, page: Int)

    /**
     * Deletes a bookmark for a specific page.
     *
     * @return true if a bookmark was deleted, false if no bookmark was found
     */
    @NativeCoroutines
    suspend fun deleteAyahBookmark(sura: Int, ayah: Int): Boolean

    /**
     * Migrates existing ayah bookmarks to the new storage format.
     * This method should only be called once during app initialization, after
     * ayah bookmarks are added and before any changes by the user are handled.
     *
     * @param bookmarks List of ayah bookmarks to migrate
     * @throws IllegalStateException if either bookmarks or mutations tables are not empty
     */
    @NativeCoroutines
    suspend fun migrateBookmarks(bookmarks: List<AyahBookmark>)
}