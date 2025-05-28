package com.quran.shared.persistence.repository

import com.quran.shared.persistence.model.Bookmark
import kotlinx.coroutines.flow.Flow

interface BookmarkRepository {

    // region Fetch, add and delete
    fun getAllBookmarks(): Flow<List<Bookmark>>

    suspend fun addPageBookmark(page: Int)

    suspend fun addAyahBookmark(sura: Int, ayah: Int)

    suspend fun deletePageBookmark(page: Int)

    suspend fun deleteAyahBookmark(sura: Int, ayah: Int)

    suspend fun addAll(bookmarks: List<Bookmark>)
    // endregion

    // region Synchronization-related
    suspend fun fetchMutatedBookmarks(): List<Bookmark>

    suspend fun persistedRemoteUpdates(mutations: List<Bookmark>)

    suspend fun clearLocalMutations()
    // endregion
}