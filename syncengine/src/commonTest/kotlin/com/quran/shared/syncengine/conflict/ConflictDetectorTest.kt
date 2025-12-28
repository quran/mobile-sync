@file:OptIn(kotlin.time.ExperimentalTime::class)
package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncBookmark
import com.quran.shared.syncengine.model.SyncBookmark.PageBookmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ConflictDetectorTest {
    
    @Test
    fun `getConflicts with empty lists should return empty result`() {
        // Given
        val conflictDetector = ConflictDetector(emptyList(), emptyList())
        
        // When
        val result = conflictDetector.getConflicts()
        
        // Then
        assertEquals(0, result.conflicts.size, "Number of resource conflicts")
        assertEquals(0, result.nonConflictingRemoteMutations.size, "Number of other remote mutations")
        assertEquals(0, result.nonConflictingLocalMutations.size, "Number of other local mutations")
    }
    
    @Test
    fun `getConflicts with different pages should return no conflicts`() {
        // Given
        val remoteModelMutations = listOf(
            RemoteModelMutation<SyncBookmark>(
                model = PageBookmark(id = "remote-1", page = 10, lastModified = Instant.fromEpochSeconds(1000)),
                remoteID = "remote-1",
                mutation = Mutation.CREATED
            )
        )
        val localModelMutations = listOf(
            LocalModelMutation<SyncBookmark>(
                model = PageBookmark(id = "local-1", page = 20, lastModified = Instant.fromEpochSeconds(1001)),
                remoteID = null,
                localID = "local-1",
                mutation = Mutation.CREATED
            )
        )
        val conflictDetector = ConflictDetector(remoteModelMutations, localModelMutations)
        
        // When
        val result = conflictDetector.getConflicts()
        
        // Then
        assertEquals(0, result.conflicts.size, "Number of resource conflicts")
        assertEquals(1, result.nonConflictingRemoteMutations.size, "Number of other remote mutations")
        assertEquals(1, result.nonConflictingLocalMutations.size, "Number of other local mutations")
    }
    
    @Test
    fun `getConflicts with creation events for same page should detect conflict`() {
        // Given
        val remoteModelMutations = listOf(
            RemoteModelMutation<SyncBookmark>(
                model = PageBookmark(id = "remote-1", page = 10, lastModified = Instant.fromEpochSeconds(1000)),
                remoteID = "remote-1",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation<SyncBookmark>(
                model = PageBookmark(id = "remote-2", page = 20, lastModified = Instant.fromEpochSeconds(1001)),
                remoteID = "remote-2",
                mutation = Mutation.CREATED
            )
        )
        val localModelMutations = listOf(
            LocalModelMutation<SyncBookmark>(
                model = PageBookmark(id = "local-1", page = 10, lastModified = Instant.fromEpochSeconds(1003)),
                remoteID = null,
                localID = "local-1",
                mutation = Mutation.CREATED
            ),
            LocalModelMutation<SyncBookmark>(
                model = PageBookmark(id = "local-2", page = 40, lastModified = Instant.fromEpochSeconds(1004)),
                remoteID = null,
                localID = "local-2",
                mutation = Mutation.CREATED
            )
        )
        val conflictDetector = ConflictDetector(remoteModelMutations, localModelMutations)
        
        // When
        val result = conflictDetector.getConflicts()
        
        // Then
        assertEquals(1, result.conflicts.size, "Number of resource conflicts")
        val resourceConflict = result.conflicts.first()
        assertEquals(1, resourceConflict.localMutations.size, "Number of local mutations in conflict")
        assertEquals(1, resourceConflict.remoteMutations.size, "Number of remote mutations in conflict")
        assertEquals(10, resourceConflict.localMutations.first().model.pageOrThrow(), "Page number of local mutation")
        assertEquals(10, resourceConflict.remoteMutations.first().model.pageOrThrow(), "Page number of remote mutation")
        
        // Verify other mutations
        assertEquals(1, result.nonConflictingRemoteMutations.size, "Number of other remote mutations")
        assertEquals(1, result.nonConflictingLocalMutations.size, "Number of other local mutations")
    }
    
    @Test
    fun `getConflicts with multiple remote mutations for same page should group them together`() {
        // Given
        val remoteModelMutations = listOf(
            RemoteModelMutation<SyncBookmark>(
                model = PageBookmark(id = "remote-1", page = 10, lastModified = Instant.fromEpochSeconds(1000)),
                remoteID = "remote-1",
                mutation = Mutation.DELETED
            ),
            RemoteModelMutation<SyncBookmark>(
                model = PageBookmark(id = "remote-2", page = 10, lastModified = Instant.fromEpochSeconds(1001)),
                remoteID = "remote-2",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation<SyncBookmark>(
                model = PageBookmark(id = "remote-3", page = 20, lastModified = Instant.fromEpochSeconds(1002)),
                remoteID = "remote-3",
                mutation = Mutation.CREATED
            )
        )
        val localModelMutations = listOf(
            LocalModelMutation<SyncBookmark>(
                model = PageBookmark(id = "local-1", page = 10, lastModified = Instant.fromEpochSeconds(200)),
                remoteID = null,
                localID = "local-1",
                mutation = Mutation.CREATED
            )
        )
        val conflictDetector = ConflictDetector(remoteModelMutations, localModelMutations)
        
        // When
        val result = conflictDetector.getConflicts()
        
        // Then
        assertEquals(1, result.conflicts.size, "Number of resource conflicts")
        val resourceConflict = result.conflicts.first()
        assertEquals(1, resourceConflict.localMutations.size, "Number of local mutations in conflict")
        assertEquals(2, resourceConflict.remoteMutations.size, "Number of remote mutations in conflict")
        assertEquals(10, resourceConflict.localMutations.first().model.pageOrThrow(), "Page number of local mutation")
        
        // Verify remote mutations are grouped correctly
        val remoteIDs = resourceConflict.remoteMutations.map { it.remoteID }.toSet()
        assertEquals(setOf("remote-1", "remote-2"), remoteIDs, "Remote IDs in conflict group")
        
        // Verify other mutations
        assertEquals(1, result.nonConflictingRemoteMutations.size, "Number of other remote mutations")
        assertEquals(0, result.nonConflictingLocalMutations.size, "Number of other local mutations")
    }
    
    @Test
    fun `getConflicts with delete and create events on both sides for same page should detect conflict`() {
        // Given
        val remoteModelMutations = listOf(
            RemoteModelMutation<SyncBookmark>(
                model = PageBookmark(id = "remote-1", page = 10, lastModified = Instant.fromEpochSeconds(1000)),
                remoteID = "remote-1",
                mutation = Mutation.DELETED
            ),
            RemoteModelMutation<SyncBookmark>(
                model = PageBookmark(id = "remote-2", page = 10, lastModified = Instant.fromEpochSeconds(1001)),
                remoteID = "remote-2",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation<SyncBookmark>(
                model = PageBookmark(id = "remote-3", page = 20, lastModified = Instant.fromEpochSeconds(1002)),
                remoteID = "remote-3",
                mutation = Mutation.CREATED
            )
        )
        val localModelMutations = listOf(
            LocalModelMutation<SyncBookmark>(
                model = PageBookmark(id = "remote-1", page = 10, lastModified = Instant.fromEpochSeconds(1003)),
                remoteID = "remote-1",
                localID = "local-1",
                mutation = Mutation.DELETED
            ),
            LocalModelMutation<SyncBookmark>(
                model = PageBookmark(id = "local-2", page = 10, lastModified = Instant.fromEpochSeconds(1004)),
                remoteID = null,
                localID = "local-2",
                mutation = Mutation.CREATED
            ),
            LocalModelMutation<SyncBookmark>(
                model = PageBookmark(id = "local-3", page = 30, lastModified = Instant.fromEpochSeconds(1005)),
                remoteID = null,
                localID = "local-3",
                mutation = Mutation.CREATED
            )
        )
        val conflictDetector = ConflictDetector(remoteModelMutations, localModelMutations)
        
        // When
        val result = conflictDetector.getConflicts()
        
        // Then
        assertEquals(1, result.conflicts.size, "Number of resource conflicts")
        val resourceConflict = result.conflicts.first()
        assertEquals(2, resourceConflict.localMutations.size, "Number of local mutations in conflict")
        assertEquals(2, resourceConflict.remoteMutations.size, "Number of remote mutations in conflict")
        assertEquals(10, resourceConflict.localMutations.first().model.pageOrThrow(), "Page number of local mutation")
        
        // Verify other mutations
        assertEquals(1, result.nonConflictingRemoteMutations.size, "Number of other remote mutations")
        assertEquals(1, result.nonConflictingLocalMutations.size, "Number of other local mutations")
    }
    
    @Test
    fun `getConflicts with multiple local mutations for same page should group them together`() {
        // Given
        val remoteModelMutations = listOf(
            RemoteModelMutation<SyncBookmark>(
                model = PageBookmark(id = "remote-1", page = 10, lastModified = Instant.fromEpochSeconds(1000)),
                remoteID = "remote-1",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation<SyncBookmark>(
                model = PageBookmark(id = "remote-2", page = 20, lastModified = Instant.fromEpochSeconds(1001)),
                remoteID = "remote-2",
                mutation = Mutation.CREATED
            )
        )
        val localModelMutations = listOf(
            LocalModelMutation<SyncBookmark>(
                model = PageBookmark(id = "local-1", page = 10, lastModified = Instant.fromEpochSeconds(1002)),
                remoteID = "remote-1",
                localID = "local-1",
                mutation = Mutation.DELETED
            ),
            LocalModelMutation<SyncBookmark>(
                model = PageBookmark(id = "local-2", page = 10, lastModified = Instant.fromEpochSeconds(1003)),
                remoteID = null,
                localID = "local-2",
                mutation = Mutation.CREATED
            ),
            LocalModelMutation<SyncBookmark>(
                model = PageBookmark(id = "local-3", page = 30, lastModified = Instant.fromEpochSeconds(1004)),
                remoteID = null,
                localID = "local-3",
                mutation = Mutation.CREATED
            )
        )
        val conflictDetector = ConflictDetector(remoteModelMutations, localModelMutations)
        
        // When
        val result = conflictDetector.getConflicts()
        
        // Then
        assertEquals(1, result.conflicts.size, "Number of resource conflicts")
        val resourceConflict = result.conflicts.first()
        assertEquals(2, resourceConflict.localMutations.size, "Number of local mutations in conflict")
        assertEquals(1, resourceConflict.remoteMutations.size, "Number of remote mutations in conflict")
        assertEquals(10, resourceConflict.localMutations.first().model.pageOrThrow(), "Page number of local mutation")
        
        // Verify local mutations are grouped correctly
        val localIDs = resourceConflict.localMutations.map { it.localID }.toSet()
        assertEquals(setOf("local-1", "local-2"), localIDs, "Local IDs in conflict group")
        
        // Verify other mutations
        assertEquals(1, result.nonConflictingRemoteMutations.size, "Number of other remote mutations")
        assertEquals(1, result.nonConflictingLocalMutations.size, "Number of other local mutations")
    }
    
    @Test
    fun `getConflicts with matching remote IDs should detect conflict even with zeroed model properties`() {
        // Given
        val remoteModelMutations = listOf(
            RemoteModelMutation<SyncBookmark>(
                model = PageBookmark(id = "remote-1", page = 0, lastModified = Instant.fromEpochSeconds(0)), // Zeroed properties for DELETE
                remoteID = "remote-1",
                mutation = Mutation.DELETED
            ),
            RemoteModelMutation<SyncBookmark>(
                model = PageBookmark(id = "remote-2", page = 20, lastModified = Instant.fromEpochSeconds(1001)),
                remoteID = "remote-2",
                mutation = Mutation.CREATED
            )
        )
        val localModelMutations = listOf(
            LocalModelMutation<SyncBookmark>(
                model = PageBookmark(id = "local-1", page = 10, lastModified = Instant.fromEpochSeconds(1002)), // Original page info for local DELETE
                remoteID = "remote-1", // Same remote ID as remote mutation
                localID = "local-1",
                mutation = Mutation.DELETED
            ),
            LocalModelMutation<SyncBookmark>(
                model = PageBookmark(id = "local-2", page = 30, lastModified = Instant.fromEpochSeconds(1002)),
                remoteID = null,
                localID = "local-2",
                mutation = Mutation.CREATED
            )
        )
        val conflictDetector = ConflictDetector(remoteModelMutations, localModelMutations)
        
        // When
        val result = conflictDetector.getConflicts()
        
        // Then
        assertEquals(1, result.conflicts.size, "Number of resource conflicts")
        val resourceConflict = result.conflicts.first()
        assertEquals(1, resourceConflict.localMutations.size, "Number of local mutations in conflict")
        assertEquals(1, resourceConflict.remoteMutations.size, "Number of remote mutations in conflict")
        
        // Verify the conflict is based on remote ID, not page
        val localMutation = resourceConflict.localMutations.first()
        val remoteMutation = resourceConflict.remoteMutations.first()
        
        assertEquals("remote-1", localMutation.remoteID, "Remote ID of local mutation")
        assertEquals("remote-1", remoteMutation.remoteID, "Remote ID of remote mutation")
        assertEquals(Mutation.DELETED, localMutation.mutation, "Mutation type of local mutation")
        assertEquals(Mutation.DELETED, remoteMutation.mutation, "Mutation type of remote mutation")
        
        // Verify that the local mutation retains original page info while remote mutation has zeroed properties
        assertEquals(10, localMutation.model.pageOrThrow(), "Page number of local mutation")
        assertEquals(Instant.fromEpochSeconds(1002), localMutation.model.lastModifiedOrThrow(), "Last modified of local mutation")
        assertEquals(0, remoteMutation.model.pageOrThrow(), "Page number of remote mutation")
        assertEquals(Instant.fromEpochSeconds(0), remoteMutation.model.lastModifiedOrThrow(), "Last modified of remote mutation")
        
        // Verify other mutations
        assertEquals(1, result.nonConflictingRemoteMutations.size, "Number of other remote mutations")
        assertEquals(1, result.nonConflictingLocalMutations.size, "Number of other local mutations")
    }
}

private fun SyncBookmark.pageOrThrow(): Int =
    (this as SyncBookmark.PageBookmark).page

private fun SyncBookmark.lastModifiedOrThrow(): Instant =
    when (this) {
        is SyncBookmark.PageBookmark -> lastModified
        is SyncBookmark.AyahBookmark -> lastModified
    }
