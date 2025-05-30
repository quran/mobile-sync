package com.quran.shared.persistence.repository

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.quran.shared.persistence.QuranDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

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

    @Test
    fun `adding should not duplicate bookmarks`() = runTest {
        // Add initial page bookmark
        database.bookmarks_mutationsQueries.createBookmark(null, null, 12, null)
        
        // Try to add the same page bookmark again
        assertFailsWith<DuplicateBookmarkException> {
            repository.addPageBookmark(12)
        }
        
        // Verify only one bookmark exists
        val bookmarks = repository.getAllBookmarks().first()
        assertEquals(1, bookmarks.size, "Should only have one bookmark")
        assertEquals(12, bookmarks[0].page, "Should be page 12")
        assertTrue(bookmarks[0].isPageBookmark, "Should be a page bookmark")
        
        // Add an ayah bookmark
        repository.addAyahBookmark(1, 1)
        
        // Try to add the same ayah bookmark multiple times
        assertFailsWith<DuplicateBookmarkException> {
            repository.addAyahBookmark(1, 1)
        }

        // Verify only one ayah bookmark was added
        val updatedBookmarks = repository.getAllBookmarks().first()
        assertEquals(2, updatedBookmarks.size, "Should have two bookmarks total")
        assertEquals(1, updatedBookmarks.count { it.isAyahBookmark }, "Should have one ayah bookmark")
        assertEquals(1, updatedBookmarks.count { it.isPageBookmark }, "Should have one page bookmark")

        database.bookmarksQueries.addBookmark("rem_id_1", null, null, 105, 10_000L)
        database.bookmarksQueries.addBookmark("rem_id_2", 9, 50, null, 10_050L)

        assertFailsWith<DuplicateBookmarkException>{
            repository.addPageBookmark(105)
        }
        assertFailsWith<DuplicateBookmarkException> {
            repository.addAyahBookmark(9, 50)
        }
    }

    @Test
    fun `deleting bookmarks removes them from the database`() = runTest {
        // Add some bookmarks first
        database.bookmarks_mutationsQueries.createBookmark(null, null, 12, null)
        database.bookmarks_mutationsQueries.createBookmark(1, 1, null, null)
        database.bookmarks_mutationsQueries.createBookmark(2, 2, null, null)
        
        // Delete a page bookmark
        repository.deletePageBookmark(12)
        var bookmarks = repository.getAllBookmarks().first()
        assertEquals(2, bookmarks.size, "Should have two bookmarks after deleting page bookmark")
        assertTrue(bookmarks.none { it.page == 12 }, "Page bookmark should be deleted")
        
        // Delete an ayah bookmark
        repository.deleteAyahBookmark(1, 1)
        bookmarks = repository.getAllBookmarks().first()
        assertEquals(1, bookmarks.size, "Should have one bookmark after deleting ayah bookmark")
        assertTrue(bookmarks.none { it.sura == 1 && it.ayah == 1 }, "Ayah bookmark should be deleted")
        
        // Try to delete non-existent bookmarks
        assertFailsWith<BookmarkNotFoundException> {
            repository.deletePageBookmark(999) // Non-existent page
        }

        assertFailsWith<BookmarkNotFoundException> {
            repository.deleteAyahBookmark(999, 999) // Non-existent ayah
        }

        // Verify state hasn't changed
        bookmarks = repository.getAllBookmarks().first()
        assertEquals(1, bookmarks.size)
        assertTrue(bookmarks[0].sura == 2 && bookmarks[0].ayah == 2)
    }

    @Test
    fun `deleting bookmarks from persisted bookmarks`() = runTest {
        database.bookmarksQueries.addBookmark("rem_id_1", null, null, 10, 10_000L)
        database.bookmarksQueries.addBookmark("rem_id_2", null, null, 15, 10_005L)
        database.bookmarksQueries.addBookmark("rem_id_3", 20, 3, null, 200_000L)

        repository.deletePageBookmark(10)
        repository.deleteAyahBookmark(20, 3)

        val bookmarks = repository.getAllBookmarks().first()
        assertTrue(bookmarks.count() == 1)
        assertEquals(15, bookmarks[0].page)

        assertFailsWith<BookmarkNotFoundException> {
            // Deleting a non-existent bookmark
            repository.deleteAyahBookmark(10, 5)
        }
        assertFailsWith<BookmarkNotFoundException> {
            // Deleting a deleted bookmark
            repository.deletePageBookmark(10)
        }

        val mutations = database.bookmarks_mutationsQueries.getBookmarksMutations().executeAsList()
        assertEquals(listOf(10L), mutations.mapNotNull { it.page })
        assertEquals(listOf(20L), mutations.mapNotNull { it.sura })
        assertEquals(listOf(3L), mutations.mapNotNull { it.ayah })
        assertEquals(setOf("rem_id_1", "rem_id_3"), mutations.map { it.remote_id }.toSet(),
            "Expected the remote IDs to be recorded in the mutations table.")
    }
}