package com.quran.shared.persistence.repository.bookmark.repository

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.input.RemoteBookmark
import com.quran.shared.persistence.model.Bookmark
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
     * Returns a flow of all bookmarks for observation.
     */
    @NativeCoroutines
    fun getBookmarksFlow(): Flow<List<Bookmark>>

    /**
     * Add a bookmark for a given sura and ayah.
     *
     * @param sura the sura number
     * @param ayah the ayah number
     * @return the [Bookmark.AyahBookmark]
     */
    @NativeCoroutines
    suspend fun addBookmark(sura: Int, ayah: Int): Bookmark.AyahBookmark

    /**
     * Delete a bookmark for a specific sura and ayah.
     *
     * @return a boolean denoting success
     */
    @NativeCoroutines
    suspend fun deleteBookmark(sura: Int, ayah: Int): Boolean
}
