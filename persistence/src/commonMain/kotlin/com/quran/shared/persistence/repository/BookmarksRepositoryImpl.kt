package com.quran.shared.persistence.repository

import app.cash.sqldelight.coroutines.asFlow
import com.quran.shared.persistence.Bookmarks
import com.quran.shared.persistence.Bookmarks_mutations
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.model.BookmarkLocalMutation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class BookmarksRepositoryImpl(
    private val database: QuranDatabase
) : BookmarkRepository {
    override fun getAllBookmarks(): Flow<List<Bookmark>> {
        val persistedFlow = database.bookmarksQueries.getBookmarks()
            .asFlow()
            .map { query ->
                query.executeAsList().map { it.toBookmark() }
            }
        val mutatedFlow = database.bookmarks_mutationsQueries.getBookmarksMutations()
            .asFlow()
            .map { query ->
                query.executeAsList().map { it.toBookmark() }
            }

        return persistedFlow.combine(mutatedFlow) { persistedBookmarks, mutatedBookmarks ->
            val deletedRemoteIDs = mutatedBookmarks
                .filter { it.localMutation == BookmarkLocalMutation.DELETED }
                .mapNotNull { it.remoteId }
                .toSet()

            persistedBookmarks.filter { it.remoteId !in deletedRemoteIDs } +
                    mutatedBookmarks.filter { it.remoteId !in deletedRemoteIDs }
        }
    }

    override suspend fun addPageBookmark(page: Int) {
        val bookmarks = database.bookmarks_mutationsQueries.recordsForPage(page.toLong())
            .executeAsList()
        if (bookmarks.isNotEmpty() && !bookmarks.none { it.deleted == 0L }) {
            throw DuplicateBookmarkException("A bookmark already exists for page $page")
        }
        val persistedBookmarks = database.bookmarksQueries.getBookmarksForPage(page.toLong())
            .executeAsList()
        if (persistedBookmarks.isNotEmpty()) {
            // TODO: It remains to check if that is deleted.
            throw DuplicateBookmarkException("A bookmark already exists for page $page")
        }
        database.bookmarks_mutationsQueries.createBookmark(null, null, page.toLong(), null)
    }

    override suspend fun addAyahBookmark(sura: Int, ayah: Int) {
        val bookmarks = database.bookmarks_mutationsQueries.recordsForAyah(sura.toLong(), ayah.toLong())
            .executeAsList()
        if (bookmarks.isNotEmpty() && !bookmarks.none{ it.deleted == 0L }) {
            throw DuplicateBookmarkException("A bookmark already exists for ayah #$ayah of sura #$sura")
        }
        val persistedBookmarks = database.bookmarksQueries.getBookmarksForAyah(
            sura = sura.toLong(),
            ayah = ayah.toLong()
        ).executeAsList()
        if (persistedBookmarks.isNotEmpty()) {
            // TODO: It remains to check if that is deleted.
            throw DuplicateBookmarkException("A bookmark already exists for ayah #$ayah of sura #$sura")
        }
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

private fun Bookmarks_mutations.toBookmark(): Bookmark = Bookmark(
    sura = sura?.toInt(),
    ayah = ayah?.toInt(),
    page = page?.toInt(),
    remoteId = remote_id,
    localMutation = if (deleted == 1L) BookmarkLocalMutation.DELETED else BookmarkLocalMutation.CREATED,
    lastUpdated = created_at
)

private fun Bookmarks.toBookmark(): Bookmark = Bookmark(
    sura = sura?.toInt(),
    ayah = ayah?.toInt(),
    page = page?.toInt(),
    remoteId = remote_id,
    localMutation = BookmarkLocalMutation.NONE,
    lastUpdated = created_at
)