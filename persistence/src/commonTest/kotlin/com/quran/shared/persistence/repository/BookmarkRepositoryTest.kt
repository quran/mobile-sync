package com.quran.shared.persistence.repository

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.model.Bookmark
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookmarkRepositoryTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: QuranDatabase
    private lateinit var repository: BookmarkRepository

    @BeforeTest
    fun setup() {
        // Create in-memory database
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        
        // Create tables
        QuranDatabase.Schema.create(driver)
        
        // Initialize database
        database = QuranDatabase(driver)
        
        // Initialize repository
        repository = BookmarksRepositoryImpl(database)
    }

    @Test
    fun `getAllBookmarks returns empty list when no bookmarks exist`() = runTest {
        val bookmarks = repository.getAllBookmarks().first()
        assertTrue(bookmarks.isEmpty())
    }

    @Test
    fun `addPageBookmark adds page bookmark to database`() = runTest {
        val page = 1
        val bookmark = repository.addPageBookmark(page)
        assertEquals(page, bookmark.page)
    }

    @Test
    fun `addAyahBookmark adds ayah bookmark to database`() = runTest {
//        val sura = 1
//        val ayah = 1
//        val bookmark = repository.addAyahBookmark(sura, ayah)
//        assertEquals(sura, bookmark.sura)
//        assertEquals(ayah, bookmark.ayah)
        assertTrue(false)
    }

    @Test
    fun `addAll adds multiple bookmarks to database`() = runTest {
//        val bookmarks = listOf(
//            Bookmark(page = 1),
//            Bookmark(sura = 1, ayah = 1)
//        )
//        repository.addAll(bookmarks)
//        val result = repository.getAllBookmarks().first()
//        assertEquals(bookmarks.size, result.size)
    }

    @Test
    fun `fetchMutatedBookmarks returns empty list when no mutations exist`() = runTest {
//        val mutations = repository.fetchMutatedBookmarks()
//        assertTrue(mutations.isEmpty())
    }

    @Test
    fun `persistedRemoteUpdates marks mutations as persisted`() = runTest {
//        val mutations = listOf(
//            Bookmark(page = 1),
//            Bookmark(sura = 1, ayah = 1)
//        )
//        repository.persistedRemoteUpdates(mutations)
//        val result = repository.fetchMutatedBookmarks()
//        assertTrue(result.isEmpty())
    }

    @Test
    fun `clearLocalMutations removes all mutations from database`() = runTest {
//        repository.clearLocalMutations()
//        val mutations = repository.fetchMutatedBookmarks()
//        assertTrue(mutations.isEmpty())
    }

    @Test
    fun `deletePageBookmark removes page bookmark from database`() = runTest {
//        val page = 1
//        repository.addPageBookmark(page)
//        repository.deletePageBookmark(page)
//        val bookmarks = repository.getAllBookmarks().first()
//        assertTrue(bookmarks.isEmpty())
    }

    @Test
    fun `deleteAyahBookmark removes ayah bookmark from database`() = runTest {
//        val sura = 1
//        val ayah = 1
//        repository.addAyahBookmark(sura, ayah)
//        repository.deleteAyahBookmark(sura, ayah)
//        val bookmarks = repository.getAllBookmarks().first()
//        assertTrue(bookmarks.isEmpty())
    }
} 