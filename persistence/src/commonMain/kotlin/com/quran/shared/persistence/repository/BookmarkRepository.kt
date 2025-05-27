package com.quran.shared.persistence.repository

import com.quran.shared.persistence.model.Bookmark
import kotlinx.coroutines.flow.Flow

interface BookmarkRepository {
    fun getAllBookmarks(): Flow<List<Bookmark>>

    suspend fun addPageBookmark(sura: Int): Bookmark

    suspend fun addAyahBookmark(sura: Int, ayah: Int): Bookmark

    suspend fun deleteBookmark(id: Long)

    suspend fun fetchMutatedBookmarks(): List<Bookmark>

    suspend fun persistedRemoteUpdates(mutations: List<Bookmark>)

    suspend fun clearLocalMutations()

    suspend fun addAll(bookmarks: List<Bookmark>)
}