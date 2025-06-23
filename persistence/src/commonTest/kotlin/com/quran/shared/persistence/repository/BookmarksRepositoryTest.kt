package com.quran.shared.persistence.repository

import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.model.BookmarkMutation
import com.quran.shared.persistence.model.BookmarkMutationType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import com.quran.shared.persistence.TestDatabaseDriver

class BookmarksRepositoryTest {
    private lateinit var database: QuranDatabase
    private lateinit var repository: BookmarksRepository
    private lateinit var syncRepository: BookmarksSynchronizationRepository

    @BeforeTest
    fun setup() {
        database = createInMemoryDatabase()
        repository = BookmarksRepositoryImpl(database)
        syncRepository = repository as BookmarksSynchronizationRepository
    }

    @Test
    fun `getAllBookmarks returns empty list when no bookmarks exist`() = runTest {
        val bookmarks = repository.getAllBookmarks().first()
        assertTrue(bookmarks.isEmpty(), "Expected empty list when no bookmarks exist")
    }

    @Test
    fun `getAllBookmarks returns all bookmarks from single table`() = runTest {
        database.bookmarksQueries.createLocalBookmark(null, null, 11)
        database.bookmarksQueries.createRemoteBookmark("rem_id_1", 2, 50, null)
        database.bookmarksQueries.createLocalBookmark(null, null, 50)

        val bookmarks = repository.getAllBookmarks().first()
        assertEquals(3, bookmarks.size, "Expected 3 bookmarks")
        assertEquals(bookmarks.filterIsInstance<Bookmark.PageBookmark>().map { it.page }.toSet(), setOf(11, 50))
        assertEquals(1, bookmarks.filterIsInstance<Bookmark.AyahBookmark>().size)
    }

    @Test
    fun `getAllBookmarks excludes deleted bookmarks`() = runTest {
        database.bookmarksQueries.createRemoteBookmark("rem_id_1", null, null, 11)
        database.bookmarksQueries.createRemoteBookmark("rem_id_2", 2, 50, null)
        // Mark one as deleted
        database.bookmarksQueries.updateBookmarkDeleted(deleted = 1L, local_id = 1L)

        val bookmarks = repository.getAllBookmarks().first()
        assertEquals(1, bookmarks.size, "Expected only non-deleted bookmarks")
        assertTrue(bookmarks[0] is Bookmark.AyahBookmark)
    }

    @Test
    fun `adding bookmarks on an empty list`() = runTest {
        repository.addPageBookmark(10)
        var bookmarks = database.bookmarksQueries.getBookmarks().executeAsList()
        assertEquals(1, bookmarks.size)
        assertEquals(10L, bookmarks[0].page)
        assertNull(bookmarks[0].ayah)
        assertNull(bookmarks[0].sura)
        assertNull(bookmarks[0].remote_id, "Locally added bookmarks should not have remote IDs (not synced yet)")

        repository.addAyahBookmark(1, 5)
        repository.addAyahBookmark(2, 50)
        bookmarks = database.bookmarksQueries.getBookmarks().executeAsList()
        assertEquals(3, bookmarks.size)
        assertEquals(listOf(10L, null, null), bookmarks.map { it.page })
        assertEquals(listOf(null, 1L, 2L), bookmarks.map { it.sura })
        assertEquals(listOf(null, 5L, 50L), bookmarks.map { it.ayah })
        
        // Verify all locally added bookmarks don't have remote IDs (not synced yet)
        bookmarks.forEach { bookmark ->
            assertNull(bookmark.remote_id, "Locally added bookmarks should not have remote IDs (not synced yet)")
        }
    }

    @Test
    fun `adding should not duplicate bookmarks`() = runTest {
        // Add initial page bookmark
        repository.addPageBookmark(12)
        
        // Try to add the same page bookmark again
        assertFailsWith<DuplicateBookmarkException> {
            repository.addPageBookmark(12)
        }
        
        // Verify only one bookmark exists
        val bookmarks = repository.getAllBookmarks().first()
        assertEquals(1, bookmarks.size, "Should only have one bookmark")
        assertTrue(bookmarks[0] is Bookmark.PageBookmark, "Should be a page bookmark")
        assertEquals(12, (bookmarks[0] as Bookmark.PageBookmark).page, "Should be page 12")
        
        // Add an ayah bookmark
        repository.addAyahBookmark(1, 1)
        
        // Try to add the same ayah bookmark multiple times
        assertFailsWith<DuplicateBookmarkException> {
            repository.addAyahBookmark(1, 1)
        }

        // Verify only one ayah bookmark was added
        val updatedBookmarks = repository.getAllBookmarks().first()
        assertEquals(2, updatedBookmarks.size, "Should have two bookmarks total")
        assertEquals(1, updatedBookmarks.filterIsInstance<Bookmark.AyahBookmark>().size, "Should have one ayah bookmark")
        assertEquals(1, updatedBookmarks.filterIsInstance<Bookmark.PageBookmark>().size, "Should have one page bookmark")

        // Test with remote bookmarks
        database.bookmarksQueries.createRemoteBookmark("rem_id_1", null, null, 105)
        database.bookmarksQueries.createRemoteBookmark("rem_id_2", 9, 50, null)

        assertFailsWith<DuplicateBookmarkException>{
            repository.addPageBookmark(105)
        }
        assertFailsWith<DuplicateBookmarkException> {
            repository.addAyahBookmark(9, 50)
        }
    }

    @Test
    fun `deleting bookmarks removes them from the database`() = runTest {
        repository.addPageBookmark(12)
        repository.addAyahBookmark(1, 1)
        repository.addAyahBookmark(2, 2)
        
        // Delete a page bookmark
        repository.deletePageBookmark(12)
        var bookmarks = repository.getAllBookmarks().first()
        assertEquals(2, bookmarks.size, "Should have two bookmarks after deleting page bookmark")
        assertTrue(bookmarks.none { it is Bookmark.PageBookmark && it.page == 12 }, "Page bookmark should be deleted")
        
        // Delete an ayah bookmark
        repository.deleteAyahBookmark(1, 1)
        bookmarks = repository.getAllBookmarks().first()
        assertEquals(1, bookmarks.size, "Should have one bookmark after deleting ayah bookmark")
        assertTrue(bookmarks.none { it is Bookmark.AyahBookmark && it.sura == 1 && it.ayah == 1 }, "Ayah bookmark should be deleted")
        
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
        val remainingBookmark = bookmarks[0] as Bookmark.AyahBookmark
        assertTrue(remainingBookmark.sura == 2 && remainingBookmark.ayah == 2)
    }

    @Test
    fun `deleting bookmarks from remote bookmarks`() = runTest {
        database.bookmarksQueries.createRemoteBookmark("rem_id_1", null, null, 10)
        database.bookmarksQueries.createRemoteBookmark("rem_id_2", null, null, 15)
        database.bookmarksQueries.createRemoteBookmark("rem_id_3", 20, 3, null)

        repository.deletePageBookmark(10)
        repository.deleteAyahBookmark(20, 3)

        val bookmarks = repository.getAllBookmarks().first()
        assertEquals(1, bookmarks.size)
        assertTrue(bookmarks[0] is Bookmark.PageBookmark)
        assertEquals(15, (bookmarks[0] as Bookmark.PageBookmark).page)

        assertFailsWith<BookmarkNotFoundException> {
            // Deleting a non-existent bookmark
            repository.deleteAyahBookmark(10, 5)
        }
        assertFailsWith<BookmarkNotFoundException> {
            // Deleting a deleted bookmark
            repository.deletePageBookmark(10)
        }

        // Verify deleted bookmarks are marked as deleted
        val allBookmarks = database.bookmarksQueries.getBookmarks().executeAsList()
        assertEquals(1, allBookmarks.size, "Should only have one non-deleted bookmark")
        
        val deletedBookmarks = database.bookmarksQueries.getBookmarkByLocation(10L, null, null).executeAsList()
        assertEquals(1, deletedBookmarks.size, "Should have one deleted bookmark for page 10")
        assertEquals(1L, deletedBookmarks[0].deleted, "Bookmark should be marked as deleted")
    }

    @Test
    fun `adding a bookmark after deleting a remote bookmark like it`() = runTest {
        // Add a remote bookmark and mark it as deleted
        database.bookmarksQueries.createRemoteBookmark("rem_id_1", null, null, 15)
        database.bookmarksQueries.updateBookmarkDeleted(1L, 1L)
        
        // Add another bookmark
        repository.addAyahBookmark(2, 50)

        // Try to add a bookmark at the same location as the deleted one
        repository.addPageBookmark(15)

        val bookmarks = repository.getAllBookmarks().first()
        assertEquals(2, bookmarks.size)
        assertEquals(listOf(15), bookmarks.filterIsInstance<Bookmark.PageBookmark>().map { it.page })
        assertEquals(listOf(2), bookmarks.filterIsInstance<Bookmark.AyahBookmark>().map { it.sura })
    }

    @Test
    fun `fetchMutatedBookmarks returns all mutated bookmarks`() = runTest {
        val emptyResult = syncRepository.fetchMutatedBookmarks()
        database.bookmarksQueries.createRemoteBookmark("rem-id-1", null, null, 10L)
        assertTrue(emptyResult.isEmpty(), "Expected to return nothing when no mutations have been added.")

        repository.addAyahBookmark(1, 1)
        repository.addAyahBookmark(2, 2)

        repository.deletePageBookmark(10)

        val result = syncRepository.fetchMutatedBookmarks()
        
        assertEquals(3, result.size)
        assertTrue(result.any { it.sura == 1 && it.ayah == 1 && it.mutationType == BookmarkMutationType.CREATED })
        assertTrue(result.any { it.sura == 2 && it.ayah == 2 && it.mutationType == BookmarkMutationType.CREATED })
        assertTrue(result.any { it.page == 10 && it.mutationType == BookmarkMutationType.DELETED })
    }

    @Test
    fun `setToSyncedState clears local mutations`() = runTest {

    }

    @Test
    fun `clearLocalMutations removes all non-synced bookmarks`() = runTest {
        // Create some test bookmarks
        repository.addAyahBookmark(1, 1)
        repository.addAyahBookmark(2, 2)

        val beforeClear = syncRepository.fetchMutatedBookmarks()
        assertEquals(2, beforeClear.size)

        // Clear local bookmarks
        syncRepository.clearLocalMutations()

        // Verify local bookmarks are removed
        val afterClear = syncRepository.fetchMutatedBookmarks()
        assertEquals(0, afterClear.size, "Local bookmarks should be removed")
    }

    @Test
    fun `persistRemoteUpdates persists bookmarks without local mutations`() = runTest {
        val remoteBookmarks = listOf(
            BookmarkMutation(page = 2, remoteId = "remote-2", mutationType = BookmarkMutationType.CREATED, lastUpdated = 1001L),
            BookmarkMutation(sura = 1, ayah = 1, remoteId = "remote-3", mutationType = BookmarkMutationType.CREATED, lastUpdated = 1002L)
        )

        syncRepository.persistRemoteUpdates(remoteBookmarks)

        val persistedBookmarks = database.bookmarksQueries.getBookmarks().executeAsList()
        assertEquals(2, persistedBookmarks.size)
        assertTrue(persistedBookmarks.any { it.page == 2L }, "Should persist page bookmark")
        assertTrue(persistedBookmarks.any { it.sura == 1L && it.ayah == 1L }, "Should persist ayah bookmark")
    }

    @Test
    fun `persistRemoteUpdates throws when bookmarks have no remote ID`() = runTest {
        val remoteBookmarks = listOf(
            BookmarkMutation(page = 1, remoteId = null, mutationType = BookmarkMutationType.CREATED, lastUpdated = 1000L),
            BookmarkMutation(sura = 1, ayah = 1, remoteId = "remote-2", mutationType = BookmarkMutationType.CREATED, lastUpdated = 1001L)
        )

        assertFails {
            syncRepository.persistRemoteUpdates(remoteBookmarks)
        }
    }

    @Test
    fun `persistRemoteUpdates handles deletions and creations with timestamps`() = runTest {
        // First add some existing bookmarks
        database.bookmarksQueries.createRemoteBookmark("remote-1", null, null, 1L)
        database.bookmarksQueries.createRemoteBookmark("remote-2", 1L, 1L, null)

        val remoteBookmarks = listOf(
            // Delete existing page bookmark
            BookmarkMutation(page = 1, remoteId = "remote-1", mutationType = BookmarkMutationType.DELETED, lastUpdated = 2000L),
            // Create new page bookmark
            BookmarkMutation(page = 2, remoteId = "remote-3", mutationType = BookmarkMutationType.CREATED, lastUpdated = 2001L),
            // Create new ayah bookmark
            BookmarkMutation(sura = 2, ayah = 2, remoteId = "remote-4", mutationType = BookmarkMutationType.CREATED, lastUpdated = 2002L)
        )

        syncRepository.persistRemoteUpdates(remoteBookmarks)

        val persistedBookmarks = database.bookmarksQueries.getBookmarks().executeAsList()
        assertEquals(3, persistedBookmarks.size, "Should have 2 bookmarks (1 deleted, 2 new)")

        // Verify deleted bookmark is gone
        assertTrue(persistedBookmarks.none { it.remote_id == "remote-1" })

        // Verify new bookmarks with their timestamps
        val newPageBookmark = persistedBookmarks.find { it.page == 2L }
        assertEquals("remote-3", newPageBookmark?.remote_id)

        val newAyahBookmark = persistedBookmarks.find { it.sura == 2L && it.ayah == 2L }
        assertEquals("remote-4", newAyahBookmark?.remote_id)
    }

    @Test
    fun `migrateBookmarks succeeds when table is empty`() = runTest {
        val bookmarks = listOf(
            Bookmark.PageBookmark(page = 1, remoteId = null, lastUpdated = 1000L),
            Bookmark.AyahBookmark(sura = 1, ayah = 1, remoteId = null, lastUpdated = 1001L)
        )

        repository.migrateBookmarks(bookmarks)

        val migratedBookmarks = database.bookmarksQueries.getBookmarks().executeAsList()
        assertEquals(2, migratedBookmarks.size)
        
        val pageBookmark = migratedBookmarks.find { it.page == 1L }
        assertEquals(0L, pageBookmark?.deleted, "Should not be marked as deleted")
        assertNull(pageBookmark?.remote_id, "Should not have remote ID")

        val ayahBookmark = migratedBookmarks.find { it.sura == 1L && it.ayah == 1L }
        assertEquals(0L, ayahBookmark?.deleted, "Should note  be marked as deleted")
        assertNull(ayahBookmark?.remote_id, "Should not have remote ID")
    }

    @Test
    fun `migrateBookmarks fails when table is not empty`() = runTest {
        val bookmarks = listOf(
            Bookmark.PageBookmark(page = 1, remoteId = null, lastUpdated = 1000L)
        )

        database.bookmarksQueries.createRemoteBookmark("existing-1", null, null, 1L)
        assertFails("Should fail if table is not empty") {
            repository.migrateBookmarks(bookmarks)
        }
    }

    @Test
    fun `migrateBookmarks fails when bookmarks have remote IDs`() = runTest {
        val bookmarksWithRemoteId = listOf(
            Bookmark.PageBookmark(page = 1, remoteId = "remote-1", lastUpdated = 1000L)
        )
        assertFails("Should fail if bookmarks have remote IDs") {
            repository.migrateBookmarks(bookmarksWithRemoteId)
        }
    }

    @Test
    fun `getAllBookmarks flow updates as mutations occur`() = runTest {
        val bookmarksFlow = repository.getAllBookmarks()
        
        // Initial state should be empty
        assertTrue(bookmarksFlow.first().isEmpty(), "Initial state should be empty")

        // Add a page bookmark
        repository.addPageBookmark(1)
        var bookmarks = bookmarksFlow.first()
        assertEquals(1, bookmarks.size, "Should have one bookmark after adding")
        assertTrue(bookmarks[0] is Bookmark.PageBookmark, "Should be a page bookmark")
        assertEquals(1, (bookmarks[0] as Bookmark.PageBookmark).page, "Should be page 1")

        // Add an ayah bookmark
        repository.addAyahBookmark(1, 1)
        bookmarks = bookmarksFlow.first()
        assertEquals(2, bookmarks.size, "Should have two bookmarks after adding ayah")
        assertTrue(bookmarks.any { it is Bookmark.PageBookmark && it.page == 1 }, "Should have page bookmark")
        assertTrue(bookmarks.any { it is Bookmark.AyahBookmark && it.sura == 1 && it.ayah == 1 }, "Should have ayah bookmark")

        // Delete the page bookmark
        repository.deletePageBookmark(1)
        bookmarks = bookmarksFlow.first()
        assertEquals(1, bookmarks.size, "Should have one bookmark after deletion")
        assertTrue(bookmarks[0] is Bookmark.AyahBookmark, "Should be an ayah bookmark")
        val remainingBookmark = bookmarks[0] as Bookmark.AyahBookmark
        assertTrue(remainingBookmark.sura == 1 && remainingBookmark.ayah == 1, "Should only have ayah bookmark")
    }

    private fun createInMemoryDatabase(): QuranDatabase {
        // Create in-memory database using platform-specific driver
        // Due to differences to how schema is handled between iOS and
        // Android target, schema creation is delegated to the driver factory's
        // actual implementations.
        return QuranDatabase(
            TestDatabaseDriver().createDriver()
        )
    }
}