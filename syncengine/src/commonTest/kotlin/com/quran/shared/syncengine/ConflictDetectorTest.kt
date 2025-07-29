package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import kotlin.test.Test
import kotlin.test.assertEquals

class ConflictDetectorTest {
    
    @Test
    fun `getConflicts with empty lists should return empty result`() {
        // Given
        val remoteModelMutations = emptyList<RemoteModelMutation<PageBookmark>>()
        val localModelMutations = emptyList<LocalModelMutation<PageBookmark>>()
        val conflictDetector = ConflictDetector(remoteModelMutations, localModelMutations)
        
        // When
        val result = conflictDetector.getConflicts()
        
        // Then
        assertEquals(0, result.conflicts.size, "Should return empty conflicts list when both input lists are empty")
        assertEquals(0, result.nonConflictingRemoteMutations.size, "Should return empty non-conflicting remote mutations")
        assertEquals(0, result.nonConflictingLocalMutations.size, "Should return empty non-conflicting local mutations")
    }
    
    @Test
    fun `getConflicts with different pages and remote IDs should return no conflicts`() {
        // Given
        val remoteModelMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark(id = "remote-1", page = 10, lastModified = 1000L),
                remoteID = "remote-1",
                mutation = Mutation.CREATED
            )
        )
        val localModelMutations = listOf(
            LocalModelMutation(
                model = PageBookmark(id = "local-1", page = 20, lastModified = 1001L),
                remoteID = null,
                localID = "local-1",
                mutation = Mutation.CREATED
            )
        )
        val conflictDetector = ConflictDetector(remoteModelMutations, localModelMutations)
        
        // When
        val result = conflictDetector.getConflicts()
        
        // Then
        assertEquals(0, result.conflicts.size, "Should return no conflicts when mutations refer to different pages and remote IDs")
        assertEquals(1, result.nonConflictingRemoteMutations.size, "Should return the remote mutation as non-conflicting")
        assertEquals(1, result.nonConflictingLocalMutations.size, "Should return the local mutation as non-conflicting")
        assertEquals(10, result.nonConflictingRemoteMutations.first().model.page, "Non-conflicting remote mutation should be for page 10")
        assertEquals(20, result.nonConflictingLocalMutations.first().model.page, "Non-conflicting local mutation should be for page 20")
    }
    
    @Test
    fun `getConflicts with creation events for same page should detect conflict`() {
        // Given
        val remoteModelMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark(id = "remote-1", page = 10, lastModified = 1000L),
                remoteID = "remote-1",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark(id = "remote-2", page = 20, lastModified = 1001L),
                remoteID = "remote-2",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark(id = "remote-3", page = 30, lastModified = 1002L),
                remoteID = "remote-3",
                mutation = Mutation.DELETED
            )
        )
        val localModelMutations = listOf(
            LocalModelMutation(
                model = PageBookmark(id = "local-1", page = 10, lastModified = 1003L),
                remoteID = null,
                localID = "local-1",
                mutation = Mutation.CREATED
            ),
            LocalModelMutation(
                model = PageBookmark(id = "local-2", page = 40, lastModified = 1004L),
                remoteID = null,
                localID = "local-2",
                mutation = Mutation.CREATED
            )
        )
        val conflictDetector = ConflictDetector(remoteModelMutations, localModelMutations)
        
        // When
        val result = conflictDetector.getConflicts()
        
        // Then
        assertEquals(1, result.conflicts.size, "Should detect one conflict for page 10 creation events")
        val conflict = result.conflicts.first()
        assertEquals(10, conflict.remoteModelMutation.model.page, "Conflict should be for page 10")
        assertEquals(10, conflict.localModelMutation.model.page, "Conflict should be for page 10")
        assertEquals(Mutation.CREATED, conflict.remoteModelMutation.mutation, "Remote mutation should be CREATED")
        assertEquals(Mutation.CREATED, conflict.localModelMutation.mutation, "Local mutation should be CREATED")
        
        // Verify non-conflicting mutations
        assertEquals(2, result.nonConflictingRemoteMutations.size, "Should have 2 non-conflicting remote mutations")
        assertEquals(1, result.nonConflictingLocalMutations.size, "Should have 1 non-conflicting local mutation")
        
        val nonConflictingRemotePages = result.nonConflictingRemoteMutations.map { it.model.page }.toSet()
        assertEquals(setOf(20, 30), nonConflictingRemotePages, "Non-conflicting remote mutations should be for pages 20 and 30")
        
        val nonConflictingLocalPages = result.nonConflictingLocalMutations.map { it.model.page }.toSet()
        assertEquals(setOf(40), nonConflictingLocalPages, "Non-conflicting local mutation should be for page 40")
    }
} 