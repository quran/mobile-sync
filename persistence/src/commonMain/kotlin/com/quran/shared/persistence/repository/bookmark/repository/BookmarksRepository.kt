package com.quran.shared.persistence.repository.bookmark.repository

import com.quran.shared.persistence.model.AyahBookmark
import com.quran.shared.persistence.util.PlatformDateTime
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import kotlinx.coroutines.flow.Flow

interface BookmarksRepository {
    /**
     * Fetch and returns all bookmarks.
     *
     * @return List<AyahBookmark> the current list of bookmarks
     */
    @NativeCoroutines
    suspend fun getAllBookmarks(): List<AyahBookmark>

    /**
     * Returns a flow of all bookmarks for observation.
     */
    @NativeCoroutines
    fun getBookmarksFlow(): Flow<List<AyahBookmark>>

    /**
     * Add a bookmark for a given sura and ayah.
     *
     * @param sura the sura number
     * @param ayah the ayah number
     * @return the [AyahBookmark]
     */
    @NativeCoroutines
    suspend fun addBookmark(sura: Int, ayah: Int): AyahBookmark

    @NativeCoroutines
    suspend fun addBookmark(sura: Int, ayah: Int, timestamp: PlatformDateTime): AyahBookmark

    /**
     * Delete a bookmark for a specific sura and ayah.
     *
     * @return a boolean denoting success
     */
    @NativeCoroutines
    suspend fun deleteBookmark(sura: Int, ayah: Int): Boolean
}
