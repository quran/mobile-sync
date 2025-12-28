@file:OptIn(kotlin.time.ExperimentalTime::class)
package com.quran.shared.persistence.repository

import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.Page_bookmarksQueries
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepository
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepositoryImpl
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksSynchronizationRepository
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class BookmarksRepositoryTest {
    private lateinit var database: QuranDatabase
    private lateinit var repository: BookmarksRepository
    private lateinit var syncRepository: BookmarksSynchronizationRepository

    private lateinit var pageBookmarksQueries: Page_bookmarksQueries

    @BeforeTest
    fun setup() {
        database = createInMemoryDatabase()
        repository = BookmarksRepositoryImpl(database)
        syncRepository = repository as BookmarksSynchronizationRepository
        pageBookmarksQueries = database.page_bookmarksQueries
    }

    @Test
    fun `getAllBookmarks returns empty list when no bookmarks exist`() = runTest {
        val bookmarks = repository.getAllBookmarks().first()
        assertTrue(bookmarks.isEmpty(), "Expected empty list when no bookmarks exist")
    }

    @Test
    fun `getAllBookmarks returns bookmarks`() = runTest {
        pageBookmarksQueries.addNewBookmark(11)
        pageBookmarksQueries.createRemoteBookmark("rem_id_1", 50)
        pageBookmarksQueries.addNewBookmark(60)

        val bookmarks = repository.getAllBookmarks().first()
        assertEquals(3, bookmarks.size, "Expected 3 bookmarks")
        assertEquals(bookmarks.requirePageBookmarks().map { it.page }.toSet(), setOf(11, 50, 60))
    }

    @Test
    fun `getAllBookmarks excludes deleted bookmarks`() = runTest {
        pageBookmarksQueries.createRemoteBookmark("rem_id_1", 11)
        pageBookmarksQueries.createRemoteBookmark("rem_id_2", 50)
        // Mark one as deleted
        pageBookmarksQueries.setDeleted(1L)

        val bookmarks = repository.getAllBookmarks().first()
        assertEquals(1, bookmarks.size, "Expected only non-deleted bookmarks")
        assertEquals(50, bookmarks[0].requirePageBookmark().page)
    }

    @Test
    fun `adding bookmarks on an empty list`() = runTest {
        repository.addPageBookmark(10)
        var bookmarks = pageBookmarksQueries.getBookmarks().executeAsList()
        assertEquals(1, bookmarks.size)
        assertEquals(10L, bookmarks[0].page)
        assertNull(bookmarks[0].remote_id, "Locally added bookmarks should not have remote IDs (not synced yet)")

        repository.addPageBookmark(20)
        repository.addPageBookmark(30)
        bookmarks = pageBookmarksQueries.getBookmarks().executeAsList()
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
        assertEquals(12, bookmarks[0].requirePageBookmark().page, "Should only have page 12")

        // Test with remote bookmarks
        pageBookmarksQueries.createRemoteBookmark("rem_id_1", 105)
        repository.addPageBookmark(105)

        bookmarks = repository.getAllBookmarks().first()
        assertEquals(2, bookmarks.size, "Should only have one bookmark")
        assertEquals(setOf(12, 105), bookmarks.requirePageBookmarks().map { it.page }.toSet(), "Expected bookmarked pages")
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
        assertTrue(bookmarks.requirePageBookmarks().none { it.page == 12 }, "Page bookmark should be deleted")

        // Delete another page bookmark
        repository.deletePageBookmark(13)
        bookmarks = repository.getAllBookmarks().first()
        assertEquals(1, bookmarks.size, "Should have one bookmark after deleting second page bookmark")
        assertTrue(bookmarks.requirePageBookmarks().none { it.page == 13 }, "Expected page 13 bookmark to be deleted")

        // Try to delete non-existent bookmarks
        repository.deletePageBookmark(999) // Non-existent page

        // Verify state hasn't changed
        bookmarks = repository.getAllBookmarks().first()
        assertEquals(1, bookmarks.size)
        assertEquals(14, bookmarks[0].requirePageBookmark().page, "Other bookmarks should be returned")

        // Verify that no un-synced bookmark records are returned for deleted bookmarks
        val unSyncedRecords = pageBookmarksQueries.getUnsyncedBookmarks().executeAsList()
        assertEquals(1, unSyncedRecords.count(), "Only one is expected now")
        assertEquals(setOf(14L), unSyncedRecords.map { it.page }.toSet())
    }

    @Test
    fun `deleting remote bookmarks`() = runTest {
        pageBookmarksQueries.createRemoteBookmark("rem_id_1", 10)
        pageBookmarksQueries.createRemoteBookmark("rem_id_2", 15)
        pageBookmarksQueries.createRemoteBookmark("rem_id_3", 20)

        repository.deletePageBookmark(10)
        repository.deletePageBookmark(20)

        val bookmarks = repository.getAllBookmarks().first()
        assertEquals(1, bookmarks.size)
        assertEquals(15, bookmarks[0].requirePageBookmark().page)

        // Delete again
        repository.deletePageBookmark(10)

        val allBookmarks = pageBookmarksQueries.getBookmarks().executeAsList()
        assertEquals(1, allBookmarks.size, "Should only have one non-deleted bookmark")

        val deletedBookmarks = pageBookmarksQueries.getAllRecordsFor(10L).executeAsList()
        assertEquals(1, deletedBookmarks.size, "Should have one deleted bookmark for page 10")
        assertEquals(1L, deletedBookmarks[0].deleted, "Bookmark should be marked as deleted")
    }

    @Test
    fun `adding a bookmark after deleting a remote bookmark like it`() = runTest {
        // Add a remote bookmark and mark it as deleted
        pageBookmarksQueries.createRemoteBookmark("rem_id_1", 15)
        pageBookmarksQueries.setDeleted(1L)

        // Add another bookmark
        repository.addPageBookmark(25)

        // Try to add a bookmark at the same location as the deleted one
        repository.addPageBookmark(15)

        val bookmarks = repository.getAllBookmarks().first()
        assertEquals(2, bookmarks.size)
        assertEquals(listOf(15, 25), bookmarks.requirePageBookmarks().map { it.page }.sorted())

        val allRecordsPage15 = pageBookmarksQueries.getAllRecordsFor(15L).executeAsList()
        assertEquals(1, allRecordsPage15.count(), "Should only have one record for page 15")
        assertEquals(0L, allRecordsPage15[0].deleted, "Re-adding should restore delete flag to false")
    }

    @Test
    fun `fetchMutatedBookmarks returns all mutated bookmarks`() = runTest {
        val emptyResult = syncRepository.fetchMutatedBookmarks()
        pageBookmarksQueries.createRemoteBookmark("rem-id-1", 10L)
        assertTrue(emptyResult.isEmpty(), "Expected to return nothing when no mutations have been added.")

        repository.addPageBookmark(1)
        repository.addPageBookmark(2)

        repository.deletePageBookmark(10)

        val result = syncRepository.fetchMutatedBookmarks()

        assertEquals(3, result.size)
        assertTrue(result.any { it.model.requirePageBookmark().page == 1 && it.mutation == Mutation.CREATED })
        assertTrue(result.any { it.model.requirePageBookmark().page == 2 && it.mutation == Mutation.CREATED })
        assertTrue(result.any { it.model.requirePageBookmark().page == 10 && it.mutation == Mutation.DELETED })

        // Assert that all returned mutations have non-null local IDs and match the database
        result.forEach { mutation ->
            val pageBookmark = mutation.model.requirePageBookmark()
            assertNotNull(mutation.localID, "Local ID should not be null for mutation on page ${pageBookmark.page}")

            // Get the corresponding database record and verify the local ID matches
            val dbRecords = pageBookmarksQueries.getAllRecordsFor(pageBookmark.page.toLong()).executeAsList()
            val matchingRecord = dbRecords.find { it.local_id.toString() == mutation.localID }
            assertNotNull(matchingRecord, "Should find database record with matching local ID ${mutation.localID} for page ${pageBookmark.page}")
        }
    }

    @Test
    fun `migrateBookmarks succeeds when table is empty`() = runTest {
        val bookmarks = listOf(
            Bookmark.PageBookmark(
                page = 1,
                lastUpdated = Instant.fromEpochSeconds(1000).toPlatform(),
                localId = null
            ),
            Bookmark.PageBookmark(
                page = 2,
                lastUpdated = Instant.fromEpochSeconds(1001).toPlatform(),
                localId = null
            )
        )

        repository.migrateBookmarks(bookmarks)

        val migratedBookmarks = pageBookmarksQueries.getBookmarks().executeAsList()
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
            Bookmark.PageBookmark(
                page = 1,
                lastUpdated = Instant.fromEpochSeconds(1000).toPlatform(),
                localId = null
            )
        )

        pageBookmarksQueries.createRemoteBookmark("existing-1", 1L)
        assertFails("Should fail if table is not empty") {
            repository.migrateBookmarks(bookmarks)
        }
    }

    @Test
    fun `migrateBookmarks succeeds with any bookmarks`() = runTest {
        val bookmarks = listOf(
            Bookmark.PageBookmark(
                page = 1,
                lastUpdated = Instant.fromEpochSeconds(1000).toPlatform(),
                localId = null
            )
        )
        repository.migrateBookmarks(bookmarks)

        val migratedBookmarks = pageBookmarksQueries.getBookmarks().executeAsList()
        assertEquals(1, migratedBookmarks.size)
        assertEquals(1L, migratedBookmarks[0].page)
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
        assertEquals(1, bookmarks[0].requirePageBookmark().page, "Should be page 1")

        // Add another page bookmark
        repository.addPageBookmark(2)
        bookmarks = bookmarksFlow.first()
        assertEquals(2, bookmarks.size, "Should have two bookmarks after adding second page")
        assertTrue(bookmarks.requirePageBookmarks().any { it.page == 1 }, "Should have page bookmark 1")
        assertTrue(bookmarks.requirePageBookmarks().any { it.page == 2 }, "Should have page bookmark 2")

        // Delete the first page bookmark
        repository.deletePageBookmark(1)
        bookmarks = bookmarksFlow.first()
        assertEquals(1, bookmarks.size, "Should have one bookmark after deletion")
        assertEquals(2, bookmarks[0].requirePageBookmark().page, "Should only have page bookmark 2")
    }

    @Test
    fun `applyRemoteChanges committing all local mutations and nothing else`() = runTest {
        // Setup: Create local mutations
        repository.addPageBookmark(10) // Local creation
        repository.addPageBookmark(20) // Local creation

        // Create remote bookmark first, then delete it to create a deletion mutation
        pageBookmarksQueries.createRemoteBookmark("remote-30", 30L)
        repository.deletePageBookmark(30) // Local deletion of remote bookmark

        // Get the local mutations to clear
        val localMutations = syncRepository.fetchMutatedBookmarks()
        assertEquals(3, localMutations.size)

        // Action: Apply remote changes - commit the local mutations
        val updatesToPersist: List<RemoteModelMutation<Bookmark>> = listOf(
            RemoteModelMutation(
                model = Bookmark.PageBookmark(
                    page = 10,
                    lastUpdated = Instant.fromEpochSeconds(1000).toPlatform(),
                    localId = null
                ),
                remoteID = "remote-10",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = Bookmark.PageBookmark(
                    page = 20,
                    lastUpdated = Instant.fromEpochSeconds(1001).toPlatform(),
                    localId = null
                ),
                remoteID = "remote-20",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = Bookmark.PageBookmark(
                    page = 30,
                    lastUpdated = Instant.fromEpochSeconds(1002).toPlatform(),
                    localId = null
                ),
                remoteID = "remote-30",
                mutation = Mutation.DELETED
            )
        )

        syncRepository.applyRemoteChanges(updatesToPersist, localMutations)

        // Assert: Final state
        val finalBookmarks = repository.getAllBookmarks().first()
        assertEquals(2, finalBookmarks.size, "Should have 2 bookmarks after sync")
        assertEquals(setOf(10, 20), finalBookmarks.requirePageBookmarks().map { it.page }.toSet())

        // Verify no remaining mutations
        val remainingMutations = syncRepository.fetchMutatedBookmarks()
        assertEquals(0, remainingMutations.size, "Should have no remaining mutations")

        // Verify remote IDs are set correctly
        val dbBookmarks = pageBookmarksQueries.getBookmarks().executeAsList()
        assertEquals(setOf("remote-10", "remote-20"), dbBookmarks.map { it.remote_id }.toSet())
    }

    @Test
    fun `applyRemoteChanges overriding all local and committing only some of them`() = runTest {
        // Setup: Create local mutations
        repository.addPageBookmark(10) // Will be committed
        repository.addPageBookmark(20) // Will be ignored

        // Create remote bookmarks first, then delete them to create deletion mutations
        pageBookmarksQueries.createRemoteBookmark("remote-30", 30L)
        pageBookmarksQueries.createRemoteBookmark("remote-40", 40L)
        repository.deletePageBookmark(30) // Will be committed
        repository.deletePageBookmark(40) // Will be ignored

        // Get the local mutations to clear
        val localMutations = syncRepository.fetchMutatedBookmarks()
        assertEquals(4, localMutations.size)

        // Action: Apply remote changes - mix of committed and overridden
        val updatesToPersist = listOf<RemoteModelMutation<Bookmark>>(
            // Committed mutations (local state matches remote)
            RemoteModelMutation(
                model = Bookmark.PageBookmark(
                    page = 10,
                    lastUpdated = Instant.fromEpochSeconds(1000).toPlatform(),
                    localId = null
                ),
                remoteID = "remote-10",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = Bookmark.PageBookmark(
                    page = 30,
                    lastUpdated = Instant.fromEpochSeconds(1001).toPlatform(),
                    localId = null
                ),
                remoteID = "remote-30",
                mutation = Mutation.DELETED
            )
        )

        syncRepository.applyRemoteChanges(updatesToPersist, localMutations)

        // Assert: Final state
        val finalBookmarks = repository.getAllBookmarks().first()
        assertEquals(2, finalBookmarks.size, "Should have 2 bookmarks after sync")
        assertEquals(setOf(10, 40), finalBookmarks.requirePageBookmarks().map { it.page }.toSet())

        // Verify no remaining mutations
        val remainingMutations = syncRepository.fetchMutatedBookmarks()
        assertEquals(0, remainingMutations.size, "Should have no remaining mutations")

        // Verify remote IDs are set correctly
        val dbBookmarks = pageBookmarksQueries.getBookmarks().executeAsList()
        assertEquals(setOf("remote-10", "remote-40"), dbBookmarks.map { it.remote_id }.toSet())
    }

    @Test
    fun `applyRemoteChanges with new remote mutations not in local mutations`() = runTest {
        // Setup: Create some local mutations
        repository.addPageBookmark(10)

        // Create remote bookmark first, then delete it to create a deletion mutation
        pageBookmarksQueries.createRemoteBookmark("remote-20", 20L)
        repository.deletePageBookmark(20)

        // Create the remote bookmark that will be deleted by the new remote mutation
        pageBookmarksQueries.createRemoteBookmark("remote-40", 40L)

        val localMutations = syncRepository.fetchMutatedBookmarks()
        assertEquals(2, localMutations.size)

        // Action: Apply remote changes including new mutations not in local list
        val updatesToPersist = listOf<RemoteModelMutation<Bookmark>>(
            RemoteModelMutation(
                model = Bookmark.PageBookmark(
                    page = 10,
                    lastUpdated = Instant.fromEpochSeconds(1000).toPlatform(),
                    localId = null
                ),
                remoteID = "remote-10",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = Bookmark.PageBookmark(
                    page = 20,
                    lastUpdated = Instant.fromEpochSeconds(1001).toPlatform(),
                    localId = null
                ),
                remoteID = "remote-20",
                mutation = Mutation.DELETED
            ),
            // New remote mutations not in local mutations
            RemoteModelMutation(
                model = Bookmark.PageBookmark(
                    page = 30,
                    lastUpdated = Instant.fromEpochSeconds(1002).toPlatform(),
                    localId = null
                ),
                remoteID = "remote-30",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = Bookmark.PageBookmark(
                    page = 40,
                    lastUpdated = Instant.fromEpochSeconds(1003).toPlatform(),
                    localId = null
                ),
                remoteID = "remote-40",
                mutation = Mutation.DELETED
            )
        )

        syncRepository.applyRemoteChanges(updatesToPersist, localMutations)

        // Assert: Final state includes new remote mutations
        val finalBookmarks = repository.getAllBookmarks().first()
        assertEquals(2, finalBookmarks.size, "Should have 2 bookmarks after sync")
        assertEquals(setOf(10, 30), finalBookmarks.requirePageBookmarks().map { it.page }.toSet())

        // Verify no remaining mutations
        val remainingMutations = syncRepository.fetchMutatedBookmarks()
        assertEquals(0, remainingMutations.size, "Should have no remaining mutations")

        // Verify remote IDs are set correctly
        val dbBookmarks = pageBookmarksQueries.getBookmarks().executeAsList()
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
        pageBookmarksQueries.createRemoteBookmark("remote-10", 10L)
        pageBookmarksQueries.createRemoteBookmark("remote-20", 20L)
        pageBookmarksQueries.createRemoteBookmark("remote-30", 30L)

        // Create some local mutations
        repository.addPageBookmark(40)

        // Delete existing remote bookmark to create a deletion mutation
        repository.deletePageBookmark(20)

        val localMutations = syncRepository.fetchMutatedBookmarks()
        assertEquals(2, localMutations.size)

        // Action: Apply remote changes for local mutations only
        val updatesToPersist = listOf<RemoteModelMutation<Bookmark>>(
            RemoteModelMutation(
                model = Bookmark.PageBookmark(
                    page = 40,
                    lastUpdated = Instant.fromEpochSeconds(1000).toPlatform(),
                    localId = null
                ),
                remoteID = "remote-40",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = Bookmark.PageBookmark(
                    page = 20,
                    lastUpdated = Instant.fromEpochSeconds(1001).toPlatform(),
                    localId = null
                ),
                remoteID = "remote-20",
                mutation = Mutation.DELETED
            )
        )

        syncRepository.applyRemoteChanges(updatesToPersist, localMutations)

        // Assert: Final state preserves existing remote bookmarks
        val finalBookmarks = repository.getAllBookmarks().first()
        assertEquals(3, finalBookmarks.size, "Should have 3 bookmarks after sync")
        assertEquals(setOf(10, 30, 40), finalBookmarks.requirePageBookmarks().map { it.page }.toSet())

        // Verify no remaining mutations
        val remainingMutations = syncRepository.fetchMutatedBookmarks()
        assertEquals(0, remainingMutations.size, "Should have no remaining mutations")

        // Verify existing remote bookmarks are preserved
        val dbBookmarks = pageBookmarksQueries.getBookmarks().executeAsList()
        assertEquals(setOf("remote-10", "remote-30", "remote-40"), dbBookmarks.map { it.remote_id }.toSet())
    }

    @Test
    fun `PageBookmark localId is properly populated from database`() = runTest {
        // Add a bookmark and verify localId is set
        repository.addPageBookmark(10)

        val bookmarks = repository.getAllBookmarks().first()
        assertEquals(1, bookmarks.size)

        val bookmark = bookmarks[0].requirePageBookmark()
        assertEquals(10, bookmark.page)
        assertNotNull(bookmark.localId, "localId should not be null")
        assertTrue(bookmark.localId!!.isNotEmpty(), "localId should not be empty")

        // Verify the localId matches the database local_id
        val dbBookmarks = pageBookmarksQueries.getBookmarks().executeAsList()
        assertEquals(1, dbBookmarks.size)
        assertEquals(dbBookmarks[0].local_id.toString(), bookmark.localId)
    }

    @Test
    fun `test remoteResourcesExist returns correct existence map`() = runTest {
        // Arrange
        // Add some remote bookmarks
        repository.addPageBookmark(1) // This will be local
        repository.addPageBookmark(2) // This will be local

        // Simulate remote bookmarks by directly persisting them
        // Note: In a real scenario, these would come from applyRemoteChanges
        val remoteBookmark1: RemoteModelMutation<Bookmark> = RemoteModelMutation(
            model = Bookmark.PageBookmark(3, Instant.fromEpochSeconds(1000).toPlatform(), null),
            remoteID = "remote-1",
            mutation = Mutation.CREATED
        )
        val remoteBookmark2: RemoteModelMutation<Bookmark> = RemoteModelMutation(
            model = Bookmark.PageBookmark(4, Instant.fromEpochSeconds(1000).toPlatform(), null),
            remoteID = "remote-2",
            mutation = Mutation.CREATED
        )
        
        syncRepository.applyRemoteChanges(listOf(remoteBookmark1, remoteBookmark2), emptyList())
        
        // Act & Assert - Test with existing and non-existing remote IDs
        val existenceMap = syncRepository.remoteResourcesExist(listOf("remote-1", "remote-2", "non-existent"))
        assertEquals(3, existenceMap.size, "Should return existence for all requested remote IDs")
        assertEquals(existenceMap["remote-1"], true, "remote-1 should exist")
        assertEquals(existenceMap["remote-2"], true, "remote-2 should exist")
        assertEquals(existenceMap["non-existent"], false, "non-existent should not exist")
        
        // Test with empty list
        val emptyExistenceMap = syncRepository.remoteResourcesExist(emptyList())
        assertTrue(emptyExistenceMap.isEmpty(), "Should return empty map for empty input")
        
        // Test with only non-existent remote IDs
        val nonExistentExistenceMap = syncRepository.remoteResourcesExist(listOf("non-existent-1", "non-existent-2"))
        assertEquals(2, nonExistentExistenceMap.size, "Should return existence for all requested remote IDs")
        assertEquals(nonExistentExistenceMap["non-existent-1"], false, "non-existent-1 should not exist")
        assertEquals(nonExistentExistenceMap["non-existent-2"], false, "non-existent-2 should not exist")
    }

    private fun Bookmark.requirePageBookmark(): Bookmark.PageBookmark {
        assertTrue(this is Bookmark.PageBookmark, "Expected PageBookmark but was ${this::class.simpleName}")
        return this as Bookmark.PageBookmark
    }

    private fun List<Bookmark>.requirePageBookmarks(): List<Bookmark.PageBookmark> {
        assertTrue(all { it is Bookmark.PageBookmark }, "Expected only page bookmarks")
        return map { it as Bookmark.PageBookmark }
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
