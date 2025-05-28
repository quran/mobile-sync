package com.quran.shared.persistence.repository

import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.model.Bookmark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class BookmarksRepositoryImpl(
    private val database: QuranDatabase
) : BookmarkRepository {
    override fun getAllBookmarks(): Flow<List<Bookmark>> = flowOf(emptyList())

    override suspend fun addPageBookmark(page: Int): Bookmark {
        TODO("Not yet implemented")
    }

    override suspend fun addAyahBookmark(sura: Int, ayah: Int): Bookmark {
        TODO("Not yet implemented")
    }

    override suspend fun addAll(bookmarks: List<Bookmark>) {
        TODO("Not yet implemented")
    }

    override suspend fun fetchMutatedBookmarks(): List<Bookmark> {
        TODO("Not yet implemented")
    }

    override suspend fun persistedRemoteUpdates(mutations: List<Bookmark>) {
        TODO("Not yet implemented")
    }

    override suspend fun clearLocalMutations() {
        TODO("Not yet implemented")
    }

    override suspend fun deletePageBookmark(page: Int) {
        TODO("Not yet implemented")
    }

    override suspend fun deleteAyahBookmark(sura: Int, ayah: Int) {
        TODO("Not yet implemented")
    }
} 