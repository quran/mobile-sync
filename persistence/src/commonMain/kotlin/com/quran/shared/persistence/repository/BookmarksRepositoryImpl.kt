package com.quran.shared.persistence.repository

import app.cash.sqldelight.coroutines.asFlow
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.model.BookmarkLocalMutation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BookmarksRepositoryImpl(
    private val database: QuranDatabase
) : BookmarkRepository {
    override fun getAllBookmarks(): Flow<List<Bookmark>> {
        return database.bookmarks_mutationsQueries.getBookmarksMutations()
            .asFlow()
            .map { query ->
                query.executeAsList()
                    .map { mutation ->
                        Bookmark(
                        sura = mutation.sura?.toInt(),
                        ayah = mutation.ayah?.toInt(),
                        page = mutation.page?.toInt(),
                        remoteId = mutation.remote_id,
                        localMutation = if (mutation.deleted == 1L) BookmarkLocalMutation.DELETED else BookmarkLocalMutation.CREATED,
                        lastUpdated = mutation.created_at
                    )
                    }
            }
    }

    override suspend fun addPageBookmark(page: Int) {
        database.bookmarks_mutationsQueries.createBookmark(null, null, page.toLong(), null)
    }

    override suspend fun addAyahBookmark(sura: Int, ayah: Int) {
        database.bookmarks_mutationsQueries.createBookmark(sura.toLong(), ayah.toLong(), null, null)
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