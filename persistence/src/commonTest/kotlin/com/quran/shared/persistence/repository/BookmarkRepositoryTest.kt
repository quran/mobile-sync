package com.quran.shared.persistence.repository

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.model.BookmarkLocalMutation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
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

    @Test
    fun `adding a bookmark after deleting a persisted bookmark like it`() = runTest {
        database.bookmarks_mutationsQueries.createMarkAsDeletedRecord(null, null, 15, "rem_id_1")
        database.bookmarks_mutationsQueries.createBookmark(2, 50, null, null)

        repository.addPageBookmark(15)

        val bookmarks = repository.getAllBookmarks().first()
        assertEquals(2, bookmarks.count())
        assertEquals(listOf(15), bookmarks.mapNotNull { it.page })
        assertEquals(listOf(2), bookmarks.mapNotNull { it.sura })

        val mutatedBookmarksPage15 = database.bookmarks_mutationsQueries.recordsForPage(15L)
            .executeAsList()
        assertEquals(2, mutatedBookmarksPage15.count())
        assertEquals("rem_id_1", mutatedBookmarksPage15.firstOrNull { it.deleted == 1L }?.remote_id,
            "Remote ID should be set for the deleted bookmark")
    }

    @Test
    fun `fetchMutatedBookmarks returns empty list when no mutations exist`() = runTest {
        val result = repository.fetchMutatedBookmarks()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `fetchMutatedBookmarks returns all mutated bookmarks`() = runTest {
        // Create some test mutations
        database.bookmarks_mutationsQueries.createBookmark(
            sura = 1L,
            ayah = 1L,
            page = null,
            remote_id = null
        )
        database.bookmarks_mutationsQueries.createBookmark(
            sura = 2L,
            ayah = 2L,
            page = null,
            remote_id = "remote-1"
        )

        val result = repository.fetchMutatedBookmarks()
        
        assertEquals(2, result.size)
        assertTrue(result.any { it.sura == 1 && it.ayah == 1 && it.localMutation == BookmarkLocalMutation.CREATED })
        assertTrue(result.any { it.sura == 2 && it.ayah == 2 && it.localMutation == BookmarkLocalMutation.CREATED })
    }

    @Test
    fun `clearLocalMutations removes all mutations`() = runTest {
        // Create some test mutations
        database.bookmarks_mutationsQueries.createBookmark(
            sura = 1L,
            ayah = 1L,
            page = null,
            remote_id = null
        )
        database.bookmarks_mutationsQueries.createBookmark(
            sura = 2L,
            ayah = 2L,
            page = null,
            remote_id = "remote-1"
        )

        // Verify mutations exist
        val beforeClear = repository.fetchMutatedBookmarks()
        assertEquals(2, beforeClear.size)

        // Clear mutations
        repository.clearLocalMutations()

        // Verify mutations are gone
        val afterClear = repository.fetchMutatedBookmarks()
        assertTrue(afterClear.isEmpty())
    }

    @Test
    fun `persistRemoteUpdates persists bookmarks without local mutations`() = runTest {
        val remoteBookmarks = listOf(
            Bookmark(page = 1, sura = null, ayah = null, remoteId = "remote-1", localMutation = BookmarkLocalMutation.NONE, lastUpdated = 1000L),
            Bookmark(page = 2, sura = null, ayah = null, remoteId = "remote-2", localMutation = BookmarkLocalMutation.NONE, lastUpdated = 1001L),
            Bookmark(page = null, sura = 1, ayah = 1, remoteId = "remote-3", localMutation = BookmarkLocalMutation.NONE, lastUpdated = 1002L)
        )

        repository.persistRemoteUpdates(remoteBookmarks)

        val persistedBookmarks = database.bookmarksQueries.getBookmarks().executeAsList()
        assertTrue(persistedBookmarks.count() == 2)
        assertTrue(persistedBookmarks.any { it.page == 2L }, "Should persist page bookmark")
        assertTrue(persistedBookmarks.any { it.sura == 1L && it.ayah == 1L }, "Should persist ayah bookmark")
    }

    @Test
    fun `persistRemoteUpdates handles transaction rollback on error`() = runTest {
        // Add initial bookmark
        database.bookmarksQueries.addBookmark("remote-1", null, null, 1L, 1000L)

        val remoteBookmarks = listOf(
            // Valid update
            Bookmark(page = 1, sura = null, ayah = null, remoteId = "remote-1-updated", localMutation = BookmarkLocalMutation.NONE, lastUpdated = 2000L),
            // Invalid bookmark (missing required fields)
            Bookmark(page = null, sura = null, ayah = null, remoteId = "remote-2", localMutation = BookmarkLocalMutation.CREATED, lastUpdated = 2001L)
        )

        assertFails {
            // Don't care for the specific exception type.
            repository.persistRemoteUpdates(remoteBookmarks)
        }

        // Verify transaction was rolled back - original bookmark should be unchanged
        val persistedBookmarks = database.bookmarksQueries.getBookmarks().executeAsList()
        assertEquals(1, persistedBookmarks.size)
        val bookmark = persistedBookmarks.first()
        assertEquals("remote-1", bookmark.remote_id)
        assertEquals(1000L, bookmark.created_at)
    }

    @Test
    fun `persistRemoteUpdates throws when bookmarks have no remote ID`() = runTest {
        val remoteBookmarks = listOf(
            Bookmark(page = 1, sura = null, ayah = null, remoteId = null, localMutation = BookmarkLocalMutation.CREATED, lastUpdated = 1000L),
            Bookmark(page = null, sura = 1, ayah = 1, remoteId = "remote-2", localMutation = BookmarkLocalMutation.CREATED, lastUpdated = 1001L)
        )

        assertFails {
            repository.persistRemoteUpdates(remoteBookmarks)
        }
    }

    @Test
    fun `persistRemoteUpdates handles deletions and creations with timestamps`() = runTest {
        // First add some existing bookmarks
        database.bookmarksQueries.addBookmark("remote-1", null, null, 1L, 1000L)
        database.bookmarksQueries.addBookmark("remote-2", 1L, 1L, null, 1001L)

        val remoteBookmarks = listOf(
            // Delete existing page bookmark
            Bookmark(page = 1, sura = null, ayah = null, remoteId = "remote-1", localMutation = BookmarkLocalMutation.DELETED, lastUpdated = 2000L),
            // Create new page bookmark
            Bookmark(page = 2, sura = null, ayah = null, remoteId = "remote-3", localMutation = BookmarkLocalMutation.CREATED, lastUpdated = 2001L),
            // Create new ayah bookmark
            Bookmark(page = null, sura = 2, ayah = 2, remoteId = "remote-4", localMutation = BookmarkLocalMutation.CREATED, lastUpdated = 2002L)
        )

        repository.persistRemoteUpdates(remoteBookmarks)

        val persistedBookmarks = database.bookmarksQueries.getBookmarks().executeAsList()
        assertEquals(3, persistedBookmarks.size, "Should have 3 bookmarks (1 deleted, 2 new)")

        // Verify deleted bookmark is gone
        assertTrue(persistedBookmarks.none { it.remote_id == "remote-1" })

        // Verify new bookmarks with their timestamps
        val newPageBookmark = persistedBookmarks.find { it.page == 2L }
        assertEquals("remote-3", newPageBookmark?.remote_id)
        assertEquals(2001L, newPageBookmark?.created_at)

        val newAyahBookmark = persistedBookmarks.find { it.sura == 2L && it.ayah == 2L }
        assertEquals("remote-4", newAyahBookmark?.remote_id)
        assertEquals(2002L, newAyahBookmark?.created_at)
    }

    @Test
    fun `migrateBookmarks succeeds when mutations table is empty`() = runTest {
        val bookmarks = listOf(
            Bookmark(page = 1, sura = null, ayah = null, remoteId = null, localMutation = BookmarkLocalMutation.CREATED, lastUpdated = 1000L),
            Bookmark(page = null, sura = 1, ayah = 1, remoteId = null, localMutation = BookmarkLocalMutation.CREATED, lastUpdated = 1001L)
        )

        repository.migrateBookmarks(bookmarks)

        val mutations = database.bookmarks_mutationsQueries.getBookmarksMutations().executeAsList()
        assertEquals(2, mutations.size)
        
        val pageBookmark = mutations.find { it.page == 1L }
        assertEquals(0L, pageBookmark?.deleted, "Should be marked as created")

        val ayahBookmark = mutations.find { it.sura == 1L && it.ayah == 1L }
        assertEquals(0L, ayahBookmark?.deleted, "Should be marked as created")
    }

    @Test
    fun `migrateBookmarks fails when either table is not empty`() = runTest {
        val bookmarks = listOf(
            Bookmark(page = 1, sura = null, ayah = null, remoteId = null, localMutation = BookmarkLocalMutation.CREATED, lastUpdated = 1000L)
        )

        database.bookmarks_mutationsQueries.createBookmark(null, null, 1L, "existing-1")
        assertFails("Should fail if mutations table is not empty") {
            repository.migrateBookmarks(bookmarks)
        }

        database.bookmarks_mutationsQueries.clearBookmarkMutations()

        database.bookmarksQueries.addBookmark("existing-1", null, null, 1L, 1000L)
        assertFails("Should fail if persisted table is not empty") {
            repository.migrateBookmarks(bookmarks)
        }
    }

    @Test
    fun `migrateBookmarks fails when bookmarks have remote IDs or are marked as deleted`() = runTest {
        val bookmarksWithRemoteId = listOf(
            Bookmark(page = 1, sura = null, ayah = null, remoteId = "remote-1", localMutation = BookmarkLocalMutation.CREATED, lastUpdated = 1000L)
        )
        assertFails("Should fail if bookmarks have remote IDs") {
            repository.migrateBookmarks(bookmarksWithRemoteId)
        }

        val deletedBookmarks = listOf(
            Bookmark(page = 1, sura = null, ayah = null, remoteId = null, localMutation = BookmarkLocalMutation.DELETED, lastUpdated = 1000L)
        )
        assertFails("Should fail if bookmarks have DELETED as mutation.") {
            repository.migrateBookmarks(deletedBookmarks)
        }
    }
}