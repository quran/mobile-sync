package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.Instant

class ConflictDetectorTest {
    
    @Test
    fun `getConflicts with empty lists should return empty result`() {
        // Given
        val conflictDetector = ConflictDetector(emptyList(), emptyList())
        
        // When
        val result = conflictDetector.getConflicts()
        
        // Then
        assertEquals(0, result.conflictGroups.size, "Number of conflict groups")
        assertEquals(0, result.otherRemoteMutations.size, "Number of other remote mutations")
        assertEquals(0, result.otherLocalMutations.size, "Number of other local mutations")
    }
    
    @Test
    fun `getConflicts with different pages should return no conflicts`() {
        // Given
        val remoteModelMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark(id = "remote-1", page = 10, lastModified = Instant.fromEpochSeconds(1000)),
                remoteID = "remote-1",
                mutation = Mutation.CREATED
            )
        )
        val localModelMutations = listOf(
            LocalModelMutation(
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
        assertEquals(0, result.conflictGroups.size, "Number of conflict groups")
        assertEquals(1, result.otherRemoteMutations.size, "Number of other remote mutations")
        assertEquals(1, result.otherLocalMutations.size, "Number of other local mutations")
    }
    
    @Test
    fun `getConflicts with creation events for same page should detect conflict`() {
        // Given
        val remoteModelMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark(id = "remote-1", page = 10, lastModified = Instant.fromEpochSeconds(1000)),
                remoteID = "remote-1",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark(id = "remote-2", page = 20, lastModified = Instant.fromEpochSeconds(1001)),
                remoteID = "remote-2",
                mutation = Mutation.CREATED
            )
        )
        val localModelMutations = listOf(
            LocalModelMutation(
                model = PageBookmark(id = "local-1", page = 10, lastModified = Instant.fromEpochSeconds(1003)),
                remoteID = null,
                localID = "local-1",
                mutation = Mutation.CREATED
            ),
            LocalModelMutation(
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
        assertEquals(1, result.conflictGroups.size, "Number of conflict groups")
        val conflictGroup = result.conflictGroups.first()
        assertEquals(1, conflictGroup.localMutations.size, "Number of local mutations in conflict")
        assertEquals(1, conflictGroup.remoteMutations.size, "Number of remote mutations in conflict")
        assertEquals(10, conflictGroup.localMutations.first().model.page, "Page number of local mutation")
        assertEquals(10, conflictGroup.remoteMutations.first().model.page, "Page number of remote mutation")
        
        // Verify other mutations
        assertEquals(1, result.otherRemoteMutations.size, "Number of other remote mutations")
        assertEquals(1, result.otherLocalMutations.size, "Number of other local mutations")
    }
    
    @Test
    fun `getConflicts with multiple remote mutations for same page should group them together`() {
        // Given
        val remoteModelMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark(id = "remote-1", page = 10, lastModified = Instant.fromEpochSeconds(1000)),
                remoteID = "remote-1",
                mutation = Mutation.DELETED
            ),
            RemoteModelMutation(
                model = PageBookmark(id = "remote-2", page = 10, lastModified = Instant.fromEpochSeconds(1001)),
                remoteID = "remote-2",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark(id = "remote-3", page = 20, lastModified = Instant.fromEpochSeconds(1002)),
                remoteID = "remote-3",
                mutation = Mutation.CREATED
            )
        )
        val localModelMutations = listOf(
            LocalModelMutation(
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
        assertEquals(1, result.conflictGroups.size, "Number of conflict groups")
        val conflictGroup = result.conflictGroups.first()
        assertEquals(1, conflictGroup.localMutations.size, "Number of local mutations in conflict")
        assertEquals(2, conflictGroup.remoteMutations.size, "Number of remote mutations in conflict")
        assertEquals(10, conflictGroup.localMutations.first().model.page, "Page number of local mutation")
        
        // Verify remote mutations are grouped correctly
        val remoteIDs = conflictGroup.remoteMutations.map { it.remoteID }.toSet()
        assertEquals(setOf("remote-1", "remote-2"), remoteIDs, "Remote IDs in conflict group")
        
        // Verify other mutations
        assertEquals(1, result.otherRemoteMutations.size, "Number of other remote mutations")
        assertEquals(0, result.otherLocalMutations.size, "Number of other local mutations")
    }
    
    @Test
    fun `getConflicts with delete and create events on both sides for same page should detect conflict`() {
        // Given
        val remoteModelMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark(id = "remote-1", page = 10, lastModified = Instant.fromEpochSeconds(1000)),
                remoteID = "remote-1",
                mutation = Mutation.DELETED
            ),
            RemoteModelMutation(
                model = PageBookmark(id = "remote-2", page = 10, lastModified = Instant.fromEpochSeconds(1001)),
                remoteID = "remote-2",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark(id = "remote-3", page = 20, lastModified = Instant.fromEpochSeconds(1002)),
                remoteID = "remote-3",
                mutation = Mutation.CREATED
            )
        )
        val localModelMutations = listOf(
            LocalModelMutation(
                model = PageBookmark(id = "remote-1", page = 10, lastModified = Instant.fromEpochSeconds(1003)),
                remoteID = "remote-1",
                localID = "local-1",
                mutation = Mutation.DELETED
            ),
            LocalModelMutation(
                model = PageBookmark(id = "local-2", page = 10, lastModified = Instant.fromEpochSeconds(1004)),
                remoteID = null,
                localID = "local-2",
                mutation = Mutation.CREATED
            ),
            LocalModelMutation(
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
        assertEquals(1, result.conflictGroups.size, "Number of conflict groups")
        val conflictGroup = result.conflictGroups.first()
        assertEquals(2, conflictGroup.localMutations.size, "Number of local mutations in conflict")
        assertEquals(2, conflictGroup.remoteMutations.size, "Number of remote mutations in conflict")
        assertEquals(10, conflictGroup.localMutations.first().model.page, "Page number of local mutation")
        
        // Verify other mutations
        assertEquals(1, result.otherRemoteMutations.size, "Number of other remote mutations")
        assertEquals(1, result.otherLocalMutations.size, "Number of other local mutations")
    }
    
    @Test
    fun `getConflicts with multiple local mutations for same page should group them together`() {
        // Given
        val remoteModelMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark(id = "remote-1", page = 10, lastModified = Instant.fromEpochSeconds(1000)),
                remoteID = "remote-1",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark(id = "remote-2", page = 20, lastModified = Instant.fromEpochSeconds(1001)),
                remoteID = "remote-2",
                mutation = Mutation.CREATED
            )
        )
        val localModelMutations = listOf(
            LocalModelMutation(
                model = PageBookmark(id = "local-1", page = 10, lastModified = Instant.fromEpochSeconds(1002)),
                remoteID = "remote-1",
                localID = "local-1",
                mutation = Mutation.DELETED
            ),
            LocalModelMutation(
                model = PageBookmark(id = "local-2", page = 10, lastModified = Instant.fromEpochSeconds(1003)),
                remoteID = null,
                localID = "local-2",
                mutation = Mutation.CREATED
            ),
            LocalModelMutation(
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
        assertEquals(1, result.conflictGroups.size, "Number of conflict groups")
        val conflictGroup = result.conflictGroups.first()
        assertEquals(2, conflictGroup.localMutations.size, "Number of local mutations in conflict")
        assertEquals(1, conflictGroup.remoteMutations.size, "Number of remote mutations in conflict")
        assertEquals(10, conflictGroup.localMutations.first().model.page, "Page number of local mutation")
        
        // Verify local mutations are grouped correctly
        val localIDs = conflictGroup.localMutations.map { it.localID }.toSet()
        assertEquals(setOf("local-1", "local-2"), localIDs, "Local IDs in conflict group")
        
        // Verify other mutations
        assertEquals(1, result.otherRemoteMutations.size, "Number of other remote mutations")
        assertEquals(1, result.otherLocalMutations.size, "Number of other local mutations")
    }
    
    @Test
    fun `getConflicts with matching remote IDs should detect conflict even with zeroed model properties`() {
        // Given
        val remoteModelMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark(id = "remote-1", page = 0, lastModified = Instant.fromEpochSeconds(0)), // Zeroed properties for DELETE
                remoteID = "remote-1",
                mutation = Mutation.DELETED
            ),
            RemoteModelMutation(
                model = PageBookmark(id = "remote-2", page = 20, lastModified = Instant.fromEpochSeconds(1001)),
                remoteID = "remote-2",
                mutation = Mutation.CREATED
            )
        )
        val localModelMutations = listOf(
            LocalModelMutation(
                model = PageBookmark(id = "local-1", page = 10, lastModified = Instant.fromEpochSeconds(1002)), // Original page info for local DELETE
                remoteID = "remote-1", // Same remote ID as remote mutation
                localID = "local-1",
                mutation = Mutation.DELETED
            ),
            LocalModelMutation(
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
        assertEquals(1, result.conflictGroups.size, "Number of conflict groups")
        val conflictGroup = result.conflictGroups.first()
        assertEquals(1, conflictGroup.localMutations.size, "Number of local mutations in conflict")
        assertEquals(1, conflictGroup.remoteMutations.size, "Number of remote mutations in conflict")
        
        // Verify the conflict is based on remote ID, not page
        val localMutation = conflictGroup.localMutations.first()
        val remoteMutation = conflictGroup.remoteMutations.first()
        
        assertEquals("remote-1", localMutation.remoteID, "Remote ID of local mutation")
        assertEquals("remote-1", remoteMutation.remoteID, "Remote ID of remote mutation")
        assertEquals(Mutation.DELETED, localMutation.mutation, "Mutation type of local mutation")
        assertEquals(Mutation.DELETED, remoteMutation.mutation, "Mutation type of remote mutation")
        
        // Verify that the local mutation retains original page info while remote mutation has zeroed properties
        assertEquals(10, localMutation.model.page, "Page number of local mutation")
        assertEquals(Instant.fromEpochSeconds(1002), localMutation.model.lastModified, "Last modified of local mutation")
        assertEquals(0, remoteMutation.model.page, "Page number of remote mutation")
        assertEquals(Instant.fromEpochSeconds(0), remoteMutation.model.lastModified, "Last modified of remote mutation")
        
        // Verify other mutations
        assertEquals(1, result.otherRemoteMutations.size, "Number of other remote mutations")
        assertEquals(1, result.otherLocalMutations.size, "Number of other local mutations")
    }
} 