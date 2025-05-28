package com.quran.shared.persistence.repository

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.quran.shared.persistence.QuranDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun `getAllBookmarks returns items from the database`() = runTest {
        database.bookmarks_mutationsQueries.createBookmark(null, null, 11, null)
        database.bookmarks_mutationsQueries.createBookmark(1, 2, null, null)
        val bookmarks = repository.getAllBookmarks().first()
        assertTrue(bookmarks.count() == 2)
        assertTrue(bookmarks[0].isPageBookmark)
        assertTrue(bookmarks[1].isAyahBookmark)
    }

    @Test
    fun `getAllBookmarks merges items from persisted and mutations databases`() = runTest {
        database.bookmarks_mutationsQueries.createBookmark(null, null, 11, null)
        database.bookmarksQueries.addBookmark("rem_id_1", 2, 50, null, 10_000L)
        database.bookmarksQueries.addBookmark("rem_id_2", null, null, 50, 10_001L)
        database.bookmarks_mutationsQueries.createMarkAsDeletedRecord(2, 50, null, remote_id = "rem_id_1")

        val bookmarks = repository.getAllBookmarks().first()
        assertTrue(bookmarks.count() == 2,
            "Expected that one mutation should cancel of the persisted bookmarks")
        assertEquals(bookmarks.mapNotNull { it.page }.toSet(), setOf(11, 50))
        assertTrue(bookmarks.mapNotNull { it.ayah }.isEmpty())
        assertTrue(bookmarks.mapNotNull { it.sura }.isEmpty())
    }

    @Test
    fun `adding bookmarks on an empty list`() = runTest {
        repository.addPageBookmark(10)
        var bookmarks = database.bookmarks_mutationsQueries.getBookmarksMutations().executeAsList()
        assertTrue(bookmarks.count() == 1)
        assertTrue(bookmarks[0].page == 10L)
        assertNull(bookmarks[0].ayah)
        assertNull(bookmarks[0].sura)

        repository.addAyahBookmark(1, 5)
        repository.addAyahBookmark(2, 50)
        bookmarks = database.bookmarks_mutationsQueries.getBookmarksMutations().executeAsList()
        assertTrue(bookmarks.count() == 3)
        assertEquals(bookmarks.map { it.page }, listOf(10L, null, null))
        assertEquals(bookmarks.map { it.sura }, listOf(null, 1L, 2L))
        assertEquals(bookmarks.map { it.ayah }, listOf(null, 5L, 50L))
    }
} 