package com.quran.shared.persistence.repository

import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.model.PageBookmark
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.mutations.Mutation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import com.quran.shared.persistence.TestDatabaseDriver

class PageBookmarksRepositoryTest {
    private lateinit var database: QuranDatabase
    private lateinit var repository: PageBookmarksRepository
    private lateinit var syncRepository: PageBookmarksSynchronizationRepository

    @BeforeTest
    fun setup() {
        database = createInMemoryDatabase()
        repository = PageBookmarksRepositoryImpl(database)
        syncRepository = repository as PageBookmarksSynchronizationRepository
    }

    @Test
    fun `getAllBookmarks returns empty list when no bookmarks exist`() = runTest {
        val bookmarks = repository.getAllBookmarks().first()
        assertTrue(bookmarks.isEmpty(), "Expected empty list when no bookmarks exist")
    }

    @Test
    fun `getAllBookmarks returns bookmarks`() = runTest {
        database.bookmarksQueries.addNewBookmark(11)
        database.bookmarksQueries.createRemoteBookmark("rem_id_1", 50)
        database.bookmarksQueries.addNewBookmark(60)

        val bookmarks = repository.getAllBookmarks().first()
        assertEquals(3, bookmarks.size, "Expected 3 bookmarks")
        assertEquals(bookmarks.map { it.page }.toSet(), setOf(11, 50, 60))
    }

    @Test
    fun `getAllBookmarks excludes deleted bookmarks`() = runTest {
        database.bookmarksQueries.createRemoteBookmark("rem_id_1", 11)
        database.bookmarksQueries.createRemoteBookmark("rem_id_2", 50)
        // Mark one as deleted
        database.bookmarksQueries.setDeleted(1L)

        val bookmarks = repository.getAllBookmarks().first()
        assertEquals(1, bookmarks.size, "Expected only non-deleted bookmarks")
        assertEquals(50, bookmarks[0].page)
    }

    @Test
    fun `adding bookmarks on an empty list`() = runTest {
        repository.addPageBookmark(10)
        var bookmarks = database.bookmarksQueries.getBookmarks().executeAsList()
        assertEquals(1, bookmarks.size)
        assertEquals(10L, bookmarks[0].page)
        assertNull(bookmarks[0].remote_id, "Locally added bookmarks should not have remote IDs (not synced yet)")

        repository.addPageBookmark(20)
        repository.addPageBookmark(30)
        bookmarks = database.bookmarksQueries.getBookmarks().executeAsList()
        assertEquals(3, bookmarks.size)
        assertEquals(listOf(10L, 20L, 30L), bookmarks.map { it.page })
        
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
        repository.addPageBookmark(12)
        
        // Verify only one bookmark exists
        var bookmarks = repository.getAllBookmarks().first()
        assertEquals(1, bookmarks.size, "Should only have one bookmark")
        assertEquals(12, bookmarks[0].page, "Should only have page 12")

        // Test with remote bookmarks
        database.bookmarksQueries.createRemoteBookmark("rem_id_1", 105)
        repository.addPageBookmark(105)

        bookmarks = repository.getAllBookmarks().first()
        assertEquals(2, bookmarks.size, "Should only have one bookmark")
        assertEquals(setOf(12, 105), bookmarks.map{ it.page }.toSet(), "Expected bookmarked pages")
    }

    @Test
    fun `deleting local bookmarks removes them from the database`() = runTest {
        repository.addPageBookmark(12)
        repository.addPageBookmark(13)
        repository.addPageBookmark(14)
        
        // Delete a page bookmark
        repository.deletePageBookmark(12)
        var bookmarks = repository.getAllBookmarks().first()
        assertEquals(2, bookmarks.size, "Should have two bookmarks after deleting page bookmark")
        assertTrue(bookmarks.none { it.page == 12 }, "Page bookmark should be deleted")
        
        // Delete another page bookmark
        repository.deletePageBookmark(13)
        bookmarks = repository.getAllBookmarks().first()
        assertEquals(1, bookmarks.size, "Should have one bookmark after deleting second page bookmark")
        assertTrue(bookmarks.none { it.page == 13 }, "Expected page 13 bookmark to be deleted")

        // Try to delete non-existent bookmarks
        repository.deletePageBookmark(999) // Non-existent page

        // Verify state hasn't changed
        bookmarks = repository.getAllBookmarks().first()
        assertEquals(1, bookmarks.size)
        assertEquals(14, bookmarks[0].page, "Other bookmarks should be returned")

        // Verify that no un-synced bookmark records are returned for deleted bookmarks
        val unSyncedRecords = database.bookmarksQueries.getUnsyncedBookmarks().executeAsList()
        assertEquals(1, unSyncedRecords.count(), "Only one is expected now")
        assertEquals(setOf(14L), unSyncedRecords.map { it.page }.toSet())
    }

    @Test
    fun `deleting remote bookmarks`() = runTest {
        database.bookmarksQueries.createRemoteBookmark("rem_id_1", 10)
        database.bookmarksQueries.createRemoteBookmark("rem_id_2", 15)
        database.bookmarksQueries.createRemoteBookmark("rem_id_3", 20)

        repository.deletePageBookmark(10)
        repository.deletePageBookmark(20)

        val bookmarks = repository.getAllBookmarks().first()
        assertEquals(1, bookmarks.size)
        assertEquals(15, bookmarks[0].page)

        // Delete again
        repository.deletePageBookmark(10)
        
        val allBookmarks = database.bookmarksQueries.getBookmarks().executeAsList()
        assertEquals(1, allBookmarks.size, "Should only have one non-deleted bookmark")
        
        val deletedBookmarks = database.bookmarksQueries.getAllRecordsFor(10L).executeAsList()
        assertEquals(1, deletedBookmarks.size, "Should have one deleted bookmark for page 10")
        assertEquals(1L, deletedBookmarks[0].deleted, "Bookmark should be marked as deleted")
    }

    @Test
    fun `adding a bookmark after deleting a remote bookmark like it`() = runTest {
        // Add a remote bookmark and mark it as deleted
        database.bookmarksQueries.createRemoteBookmark("rem_id_1", 15)
        database.bookmarksQueries.setDeleted(1L)
        
        // Add another bookmark
        repository.addPageBookmark(25)

        // Try to add a bookmark at the same location as the deleted one
        repository.addPageBookmark(15)

        val bookmarks = repository.getAllBookmarks().first()
        assertEquals(2, bookmarks.size)
        assertEquals(listOf(15, 25), bookmarks.map { it.page }.sorted())

        val allRecordsPage15 = database.bookmarksQueries.getAllRecordsFor(15L).executeAsList()
        assertEquals(1, allRecordsPage15.count(), "Should only have one record for page 15")
        assertEquals(0L, allRecordsPage15[0].deleted, "Re-adding should restore delete flag to false")
    }

    @Test
    fun `fetchMutatedBookmarks returns all mutated bookmarks`() = runTest {
        val emptyResult = syncRepository.fetchMutatedBookmarks()
        database.bookmarksQueries.createRemoteBookmark("rem-id-1", 10L)
        assertTrue(emptyResult.isEmpty(), "Expected to return nothing when no mutations have been added.")

        repository.addPageBookmark(1)
        repository.addPageBookmark(2)

        repository.deletePageBookmark(10)

        val result = syncRepository.fetchMutatedBookmarks()
        
        assertEquals(3, result.size)
        assertTrue(result.any { it.model.page == 1 && it.mutation == Mutation.CREATED })
        assertTrue(result.any { it.model.page == 2 && it.mutation == Mutation.CREATED })
        assertTrue(result.any { it.model.page == 10 && it.mutation == Mutation.DELETED })
        
        // Assert that all returned mutations have non-null local IDs and match the database
        result.forEach { mutation ->
            assertNotNull(mutation.localID, "Local ID should not be null for mutation on page ${mutation.model.page}")
            
            // Get the corresponding database record and verify the local ID matches
            val dbRecords = database.bookmarksQueries.getAllRecordsFor(mutation.model.page.toLong()).executeAsList()
            val matchingRecord = dbRecords.find { it.local_id.toString() == mutation.localID }
            assertNotNull(matchingRecord, "Should find database record with matching local ID ${mutation.localID} for page ${mutation.model.page}")
        }
    }

    @Test
    fun `migrateBookmarks succeeds when table is empty`() = runTest {
        val bookmarks = listOf(
            PageBookmark(page = 1, lastUpdated = 1000L, localId = null),
            PageBookmark(page = 2, lastUpdated = 1001L, localId = null)
        )

        repository.migrateBookmarks(bookmarks)

        val migratedBookmarks = database.bookmarksQueries.getBookmarks().executeAsList()
        assertEquals(2, migratedBookmarks.size)
        
        val pageBookmark1 = migratedBookmarks.find { it.page == 1L }
        assertEquals(0L, pageBookmark1?.deleted, "Should not be marked as deleted")
        assertNull(pageBookmark1?.remote_id, "Should not have remote ID")

        val pageBookmark2 = migratedBookmarks.find { it.page == 2L }
        assertEquals(0L, pageBookmark2?.deleted, "Should not be marked as deleted")
        assertNull(pageBookmark2?.remote_id, "Should not have remote ID")
    }

    @Test
    fun `migrateBookmarks fails when table is not empty`() = runTest {
        val bookmarks = listOf(
            PageBookmark(page = 1, lastUpdated = 1000L, localId = null)
        )

        database.bookmarksQueries.createRemoteBookmark("existing-1", 1L)
        assertFails("Should fail if table is not empty") {
            repository.migrateBookmarks(bookmarks)
        }
    }

    @Test
    fun `migrateBookmarks fails when bookmarks have remote IDs`() = runTest {
        val bookmarksWithRemoteId = listOf(
            PageBookmark(page = 1, lastUpdated = 1000L, remoteId = "remote-1", localId = null)
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
        assertEquals(1, bookmarks[0].page, "Should be page 1")

        // Add another page bookmark
        repository.addPageBookmark(2)
        bookmarks = bookmarksFlow.first()
        assertEquals(2, bookmarks.size, "Should have two bookmarks after adding second page")
        assertTrue(bookmarks.any { it.page == 1 }, "Should have page bookmark 1")
        assertTrue(bookmarks.any { it.page == 2 }, "Should have page bookmark 2")

        // Delete the first page bookmark
        repository.deletePageBookmark(1)
        bookmarks = bookmarksFlow.first()
        assertEquals(1, bookmarks.size, "Should have one bookmark after deletion")
        assertEquals(2, bookmarks[0].page, "Should only have page bookmark 2")
    }

    @Test
    fun `applyRemoteChanges committing all local mutations and nothing else`() = runTest {
        // Setup: Create local mutations
        repository.addPageBookmark(10) // Local creation
        repository.addPageBookmark(20) // Local creation
        
        // Create remote bookmark first, then delete it to create a deletion mutation
        database.bookmarksQueries.createRemoteBookmark("remote-30", 30L)
        repository.deletePageBookmark(30) // Local deletion of remote bookmark
        
        // Get the local mutations to clear
        val localMutations = syncRepository.fetchMutatedBookmarks()
        assertEquals(3, localMutations.size)
        
        // Action: Apply remote changes - commit the local mutations
        val updatesToPersist = listOf(
            RemoteModelMutation(
                model = PageBookmark(page = 10, lastUpdated = 1000L, localId = null),
                remoteID = "remote-10",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark(page = 20, lastUpdated = 1001L, localId = null),
                remoteID = "remote-20",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark(page = 30, lastUpdated = 1002L, localId = null),
                remoteID = "remote-30",
                mutation = Mutation.DELETED
            )
        )
        
        syncRepository.applyRemoteChanges(updatesToPersist, localMutations)

        // Assert: Final state
        val finalBookmarks = repository.getAllBookmarks().first()
        assertEquals(2, finalBookmarks.size, "Should have 2 bookmarks after sync")
        assertEquals(setOf(10, 20), finalBookmarks.map { it.page }.toSet())
        
        // Verify no remaining mutations
        val remainingMutations = syncRepository.fetchMutatedBookmarks()
        assertEquals(0, remainingMutations.size, "Should have no remaining mutations")

        // Verify remote IDs are set correctly
        val dbBookmarks = database.bookmarksQueries.getBookmarks().executeAsList()
        assertEquals(setOf("remote-10", "remote-20"), dbBookmarks.map { it.remote_id }.toSet())
    }

    @Test
    fun `applyRemoteChanges overriding all local and committing only some of them`() = runTest {
        // Setup: Create local mutations
        repository.addPageBookmark(10) // Will be committed
        repository.addPageBookmark(20) // Will be ignored
        
        // Create remote bookmarks first, then delete them to create deletion mutations
        database.bookmarksQueries.createRemoteBookmark("remote-30", 30L)
        database.bookmarksQueries.createRemoteBookmark("remote-40", 40L)
        repository.deletePageBookmark(30) // Will be committed
        repository.deletePageBookmark(40) // Will be ignored
        
        // Get the local mutations to clear
        val localMutations = syncRepository.fetchMutatedBookmarks()
        assertEquals(4, localMutations.size)
        
        // Action: Apply remote changes - mix of committed and overridden
        val updatesToPersist = listOf(
            // Committed mutations (local state matches remote)
            RemoteModelMutation(
                model = PageBookmark(page = 10, lastUpdated = 1000L, localId = null),
                remoteID = "remote-10",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark(page = 30, lastUpdated = 1001L, localId = null),
                remoteID = "remote-30",
                mutation = Mutation.DELETED
            )
        )

        syncRepository.applyRemoteChanges(updatesToPersist, localMutations)

        // Assert: Final state
        val finalBookmarks = repository.getAllBookmarks().first()
        assertEquals(2, finalBookmarks.size, "Should have 2 bookmarks after sync")
        assertEquals(setOf(10, 40), finalBookmarks.map { it.page }.toSet())

        // Verify no remaining mutations
        val remainingMutations = syncRepository.fetchMutatedBookmarks()
        assertEquals(0, remainingMutations.size, "Should have no remaining mutations")

        // Verify remote IDs are set correctly
        val dbBookmarks = database.bookmarksQueries.getBookmarks().executeAsList()
        assertEquals(setOf("remote-10", "remote-40"), dbBookmarks.map { it.remote_id }.toSet())
    }

    @Test
    fun `applyRemoteChanges with new remote mutations not in local mutations`() = runTest {
        // Setup: Create some local mutations
        repository.addPageBookmark(10)
        
        // Create remote bookmark first, then delete it to create a deletion mutation
        database.bookmarksQueries.createRemoteBookmark("remote-20", 20L)
        repository.deletePageBookmark(20)

        // Create the remote bookmark that will be deleted by the new remote mutation
        database.bookmarksQueries.createRemoteBookmark("remote-40", 40L)
        
        val localMutations = syncRepository.fetchMutatedBookmarks()
        assertEquals(2, localMutations.size)
        
        // Action: Apply remote changes including new mutations not in local list
        val updatesToPersist = listOf(
            RemoteModelMutation(
                model = PageBookmark(page = 10, lastUpdated = 1000L, localId = null),
                remoteID = "remote-10",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark(page = 20, lastUpdated = 1001L, localId = null),
                remoteID = "remote-20",
                mutation = Mutation.DELETED
            ),
            // New remote mutations not in local mutations
            RemoteModelMutation(
                model = PageBookmark(page = 30, lastUpdated = 1002L, localId = null),
                remoteID = "remote-30",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark(page = 40, lastUpdated = 1003L, localId = null),
                remoteID = "remote-40",
                mutation = Mutation.DELETED
            )
        )
        
        syncRepository.applyRemoteChanges(updatesToPersist, localMutations)
        
        // Assert: Final state includes new remote mutations
        val finalBookmarks = repository.getAllBookmarks().first()
        assertEquals(2, finalBookmarks.size, "Should have 2 bookmarks after sync")
        assertEquals(setOf(10, 30), finalBookmarks.map { it.page }.toSet())
        
        // Verify no remaining mutations
        val remainingMutations = syncRepository.fetchMutatedBookmarks()
        assertEquals(0, remainingMutations.size, "Should have no remaining mutations")
        
        // Verify remote IDs are set correctly
        val dbBookmarks = database.bookmarksQueries.getBookmarks().executeAsList()
        assertEquals(setOf("remote-10", "remote-30"), dbBookmarks.map { it.remote_id }.toSet())
    }

    @Test
    fun `applyRemoteChanges with empty lists`() = runTest {
        // Setup: Create some local mutations
        repository.addPageBookmark(10)
        repository.addPageBookmark(20)
        
        val localMutations = syncRepository.fetchMutatedBookmarks()
        assertEquals(2, localMutations.size)
        
        // Action: Apply empty remote changes
        syncRepository.applyRemoteChanges(emptyList(), localMutations)
        
        // Assert: Local mutations are cleared but no new bookmarks added
        val finalBookmarks = repository.getAllBookmarks().first()
        assertEquals(0, finalBookmarks.size, "Should have no bookmarks after clearing local mutations")
        
        // Verify no remaining mutations
        val remainingMutations = syncRepository.fetchMutatedBookmarks()
        assertEquals(0, remainingMutations.size, "Should have no remaining mutations")
    }

    @Test
    fun `applyRemoteChanges preserves existing remote bookmarks not in updates`() = runTest {
        // Setup: Create existing remote bookmarks
        database.bookmarksQueries.createRemoteBookmark("remote-10", 10L)
        database.bookmarksQueries.createRemoteBookmark("remote-20", 20L)
        database.bookmarksQueries.createRemoteBookmark("remote-30", 30L)
        
        // Create some local mutations
        repository.addPageBookmark(40)
        
        // Delete existing remote bookmark to create a deletion mutation
        repository.deletePageBookmark(20)
        
        val localMutations = syncRepository.fetchMutatedBookmarks()
        assertEquals(2, localMutations.size)
        
        // Action: Apply remote changes for local mutations only
        val updatesToPersist = listOf(
            RemoteModelMutation(
                model = PageBookmark(page = 40, lastUpdated = 1000L, localId = null),
                remoteID = "remote-40",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark(page = 20, lastUpdated = 1001L, localId = null),
                remoteID = "remote-20",
                mutation = Mutation.DELETED
            )
        )
        
        syncRepository.applyRemoteChanges(updatesToPersist, localMutations)
        
        // Assert: Final state preserves existing remote bookmarks
        val finalBookmarks = repository.getAllBookmarks().first()
        assertEquals(3, finalBookmarks.size, "Should have 3 bookmarks after sync")
        assertEquals(setOf(10, 30, 40), finalBookmarks.map { it.page }.toSet())
        
        // Verify no remaining mutations
        val remainingMutations = syncRepository.fetchMutatedBookmarks()
        assertEquals(0, remainingMutations.size, "Should have no remaining mutations")
        
        // Verify existing remote bookmarks are preserved
        val dbBookmarks = database.bookmarksQueries.getBookmarks().executeAsList()
        assertEquals(setOf("remote-10", "remote-30", "remote-40"), dbBookmarks.map { it.remote_id }.toSet())
    }

    @Test
    fun `PageBookmark localId is properly populated from database`() = runTest {
        // Add a bookmark and verify localId is set
        repository.addPageBookmark(10)
        
        val bookmarks = repository.getAllBookmarks().first()
        assertEquals(1, bookmarks.size)
        
        val bookmark = bookmarks[0]
        assertEquals(10, bookmark.page)
        assertNotNull(bookmark.localId, "localId should not be null")
        assertTrue(bookmark.localId!!.isNotEmpty(), "localId should not be empty")
        
        // Verify the localId matches the database local_id
        val dbBookmarks = database.bookmarksQueries.getBookmarks().executeAsList()
        assertEquals(1, dbBookmarks.size)
        assertEquals(dbBookmarks[0].local_id.toString(), bookmark.localId)
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