package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
        assertEquals(0, result.conflictGroups.size, "Should return empty conflict groups when both input lists are empty")
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
        assertEquals(0, result.conflictGroups.size, "Should return no conflicts when mutations refer to different pages and remote IDs")
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
        assertEquals(1, result.conflictGroups.size, "Should detect one conflict group for page 10 creation events")
        val conflictGroup = result.conflictGroups.first()
        assertEquals(1, conflictGroup.localMutations.size, "Should have 1 local mutation")
        assertEquals(10, conflictGroup.localMutations.first().model.page, "Conflict group should be for page 10")
        assertEquals(1, conflictGroup.conflictingRemoteMutations.size, "Should have 1 conflicting remote mutation")
        assertEquals(10, conflictGroup.conflictingRemoteMutations.first().model.page, "Conflicting remote mutation should be for page 10")
        assertEquals(Mutation.CREATED, conflictGroup.conflictingRemoteMutations.first().mutation, "Remote mutation should be CREATED")
        assertEquals(Mutation.CREATED, conflictGroup.localMutations.first().mutation, "Local mutation should be CREATED")
        
        // Verify non-conflicting mutations
        assertEquals(2, result.nonConflictingRemoteMutations.size, "Should have 2 non-conflicting remote mutations")
        assertEquals(1, result.nonConflictingLocalMutations.size, "Should have 1 non-conflicting local mutation")
        
        val nonConflictingRemotePages = result.nonConflictingRemoteMutations.map { it.model.page }.toSet()
        assertEquals(setOf(20, 30), nonConflictingRemotePages, "Non-conflicting remote mutations should be for pages 20 and 30")
        
        val nonConflictingLocalPages = result.nonConflictingLocalMutations.map { it.model.page }.toSet()
        assertEquals(setOf(40), nonConflictingLocalPages, "Non-conflicting local mutation should be for page 40")
    }
    
    @Test
    fun `getConflicts with multiple remote mutations for same page should group them together`() {
        // Given
        val remoteModelMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark(id = "remote-1", page = 10, lastModified = 1000L),
                remoteID = "remote-1",
                mutation = Mutation.DELETED
            ),
            RemoteModelMutation(
                model = PageBookmark(id = "remote-2", page = 10, lastModified = 1001L),
                remoteID = "remote-2",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark(id = "remote-3", page = 20, lastModified = 1002L),
                remoteID = "remote-3",
                mutation = Mutation.CREATED
            )
        )
        val localModelMutations = listOf(
            LocalModelMutation(
                model = PageBookmark(id = "local-1", page = 10, lastModified = 200L),
                remoteID = null,
                localID = "local-1",
                mutation = Mutation.CREATED
            )
        )
        val conflictDetector = ConflictDetector(remoteModelMutations, localModelMutations)
        
        // When
        val result = conflictDetector.getConflicts()
        
        // Then
        assertEquals(1, result.conflictGroups.size, "Should detect one conflict group for page 10")
        val conflictGroup = result.conflictGroups.first()
        assertEquals(1, conflictGroup.localMutations.size, "Should have 1 local mutation")
        assertEquals(10, conflictGroup.localMutations.first().model.page, "Conflict group should be for page 10")
        assertEquals(2, conflictGroup.conflictingRemoteMutations.size, "Should have 2 conflicting remote mutations")
        
        val conflictingRemotePages = conflictGroup.conflictingRemoteMutations.map { it.model.page }.toSet()
        assertEquals(setOf(10), conflictingRemotePages, "All conflicting remote mutations should be for page 10")
        
        val conflictingRemoteIDs = conflictGroup.conflictingRemoteMutations.map { it.remoteID }.toSet()
        assertEquals(setOf("remote-1", "remote-2"), conflictingRemoteIDs, "Should include both remote mutations for page 10")
        
        // Verify the order and types of mutations
        val firstRemoteMutation = conflictGroup.conflictingRemoteMutations.first()
        val secondRemoteMutation = conflictGroup.conflictingRemoteMutations.last()
        assertEquals(Mutation.DELETED, firstRemoteMutation.mutation, "First remote mutation should be DELETED (older)")
        assertEquals(Mutation.CREATED, secondRemoteMutation.mutation, "Second remote mutation should be CREATED (newer)")
        assertEquals(1000L, firstRemoteMutation.model.lastModified, "First mutation should have older timestamp")
        assertEquals(1001L, secondRemoteMutation.model.lastModified, "Second mutation should have newer timestamp")
        
        // Verify non-conflicting mutations
        assertEquals(1, result.nonConflictingRemoteMutations.size, "Should have 1 non-conflicting remote mutation")
        assertEquals(0, result.nonConflictingLocalMutations.size, "Should have 0 non-conflicting local mutations")
        
        assertEquals(20, result.nonConflictingRemoteMutations.first().model.page, "Non-conflicting remote mutation should be for page 20")
    }
    
    @Test
    fun `getConflicts with delete and create events on both sides for same page should detect conflict`() {
        // Given
        val remoteModelMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark(id = "remote-1", page = 10, lastModified = 1000L),
                remoteID = "remote-1",
                mutation = Mutation.DELETED
            ),
            RemoteModelMutation(
                model = PageBookmark(id = "remote-2", page = 10, lastModified = 1001L),
                remoteID = "remote-2",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark(id = "remote-3", page = 20, lastModified = 1002L),
                remoteID = "remote-3",
                mutation = Mutation.CREATED
            )
        )
        val localModelMutations = listOf(
            LocalModelMutation(
                model = PageBookmark(id = "remote-1", page = 10, lastModified = 1003L),
                remoteID = "remote-1",
                localID = "local-1",
                mutation = Mutation.DELETED
            ),
            LocalModelMutation(
                model = PageBookmark(id = "local-2", page = 10, lastModified = 1004L),
                remoteID = null,
                localID = "local-2",
                mutation = Mutation.CREATED
            ),
            LocalModelMutation(
                model = PageBookmark(id = "local-3", page = 30, lastModified = 1005L),
                remoteID = null,
                localID = "local-3",
                mutation = Mutation.CREATED
            )
        )
        val conflictDetector = ConflictDetector(remoteModelMutations, localModelMutations)
        
        // When
        val result = conflictDetector.getConflicts()
        
        // Then
        assertEquals(1, result.conflictGroups.size, "Should detect 1 conflict group for page 10")
        
        val conflictGroup = result.conflictGroups.first()
        assertEquals(2, conflictGroup.localMutations.size, "Should have 2 local mutations")
        assertEquals(10, conflictGroup.localMutations.first().model.page, "Conflict group should be for page 10")
        assertEquals(2, conflictGroup.conflictingRemoteMutations.size, "Should have 2 conflicting remote mutations")
        
        // Verify the remote mutations in the conflict group
        val conflictingRemotePages = conflictGroup.conflictingRemoteMutations.map { it.model.page }.toSet()
        assertEquals(setOf(10), conflictingRemotePages, "All conflicting remote mutations should be for page 10")
        
        val conflictingRemoteIDs = conflictGroup.conflictingRemoteMutations.map { it.remoteID }.toSet()
        assertEquals(setOf("remote-1", "remote-2"), conflictingRemoteIDs, "Should include both remote mutations for page 10")
        
        // Verify non-conflicting mutations
        assertEquals(1, result.nonConflictingRemoteMutations.size, "Should have 1 non-conflicting remote mutation")
        assertEquals(1, result.nonConflictingLocalMutations.size, "Should have 1 non-conflicting local mutation")
        
        assertEquals(20, result.nonConflictingRemoteMutations.first().model.page, "Non-conflicting remote mutation should be for page 20")
        assertEquals(30, result.nonConflictingLocalMutations.first().model.page, "Non-conflicting local mutation should be for page 30")
    }
    
    @Test
    fun `getConflicts with delete and create on local side and single delete on remote side should detect conflict`() {
        // Given
        val remoteModelMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark(id = "remote-1", page = 10, lastModified = 1000L),
                remoteID = "remote-1",
                mutation = Mutation.DELETED
            ),
            RemoteModelMutation(
                model = PageBookmark(id = "remote-2", page = 20, lastModified = 1001L),
                remoteID = "remote-2",
                mutation = Mutation.CREATED
            )
        )
        val localModelMutations = listOf(
            LocalModelMutation(
                model = PageBookmark(id = "local-1", page = 10, lastModified = 1002L),
                remoteID = "remote-1",
                localID = "local-1",
                mutation = Mutation.DELETED
            ),
            LocalModelMutation(
                model = PageBookmark(id = "local-2", page = 10, lastModified = 1003L),
                remoteID = null,
                localID = "local-2",
                mutation = Mutation.CREATED
            ),
            LocalModelMutation(
                model = PageBookmark(id = "local-3", page = 30, lastModified = 1004L),
                remoteID = null,
                localID = "local-3",
                mutation = Mutation.CREATED
            )
        )
        val conflictDetector = ConflictDetector(remoteModelMutations, localModelMutations)
        
        // When
        val result = conflictDetector.getConflicts()
        
        // Then
        assertEquals(1, result.conflictGroups.size, "Should detect 1 conflict group for page 10")
        
        val conflictGroup = result.conflictGroups.first()
        assertEquals(2, conflictGroup.localMutations.size, "Should have 2 local mutations")
        assertEquals(10, conflictGroup.localMutations.first().model.page, "Conflict group should be for page 10")
        assertEquals(1, conflictGroup.conflictingRemoteMutations.size, "Should have 1 conflicting remote mutation")
        
        // Verify the remote mutation in the conflict group
        val conflictingRemoteMutation = conflictGroup.conflictingRemoteMutations.first()
        assertEquals(10, conflictingRemoteMutation.model.page, "Conflicting remote mutation should be for page 10")
        assertEquals(Mutation.DELETED, conflictingRemoteMutation.mutation, "Remote mutation should be DELETED")
        assertEquals("remote-1", conflictingRemoteMutation.remoteID, "Remote mutation should have correct remote ID")
        
        // Verify non-conflicting mutations
        assertEquals(1, result.nonConflictingRemoteMutations.size, "Should have 1 non-conflicting remote mutation")
        assertEquals(1, result.nonConflictingLocalMutations.size, "Should have 1 non-conflicting local mutation")
        
        assertEquals(20, result.nonConflictingRemoteMutations.first().model.page, "Non-conflicting remote mutation should be for page 20")
        assertEquals(30, result.nonConflictingLocalMutations.first().model.page, "Non-conflicting local mutation should be for page 30")
    }
    
    @Test
    fun `getConflicts with multiple local mutations for same page should group them together`() {
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
            )
        )
        val localModelMutations = listOf(
            LocalModelMutation(
                model = PageBookmark(id = "local-1", page = 10, lastModified = 1002L),
                remoteID = "remote-1",
                localID = "local-1",
                mutation = Mutation.DELETED
            ),
            LocalModelMutation(
                model = PageBookmark(id = "local-2", page = 10, lastModified = 1003L),
                remoteID = null,
                localID = "local-2",
                mutation = Mutation.CREATED
            ),
            LocalModelMutation(
                model = PageBookmark(id = "local-3", page = 30, lastModified = 1004L),
                remoteID = null,
                localID = "local-3",
                mutation = Mutation.CREATED
            )
        )
        val conflictDetector = ConflictDetector(remoteModelMutations, localModelMutations)
        
        // When
        val result = conflictDetector.getConflicts()
        
        // Then
        assertEquals(1, result.conflictGroups.size, "Should detect 1 conflict group for page 10")
        
        val conflictGroup = result.conflictGroups.first()
        assertEquals(2, conflictGroup.localMutations.size, "Should have 2 local mutations for page 10")
        assertEquals(1, conflictGroup.conflictingRemoteMutations.size, "Should have 1 conflicting remote mutation")
        
        // Verify local mutations in the conflict group
        val localMutationPages = conflictGroup.localMutations.map { it.model.page }.toSet()
        assertEquals(setOf(10), localMutationPages, "All local mutations should be for page 10")
        
        val localMutationTypes = conflictGroup.localMutations.map { it.mutation }.toSet()
        assertEquals(setOf(Mutation.DELETED, Mutation.CREATED), localMutationTypes, "Should have both DELETE and CREATE local mutations")
        
        val localMutationIDs = conflictGroup.localMutations.map { it.localID }.toSet()
        assertEquals(setOf("local-1", "local-2"), localMutationIDs, "Should include both local mutations")
        
        // Verify remote mutation in the conflict group
        val conflictingRemoteMutation = conflictGroup.conflictingRemoteMutations.first()
        assertEquals(10, conflictingRemoteMutation.model.page, "Conflicting remote mutation should be for page 10")
        assertEquals(Mutation.CREATED, conflictingRemoteMutation.mutation, "Remote mutation should be CREATED")
        
        // Verify non-conflicting mutations
        assertEquals(1, result.nonConflictingRemoteMutations.size, "Should have 1 non-conflicting remote mutation")
        assertEquals(1, result.nonConflictingLocalMutations.size, "Should have 1 non-conflicting local mutation")
        
        assertEquals(20, result.nonConflictingRemoteMutations.first().model.page, "Non-conflicting remote mutation should be for page 20")
        assertEquals(30, result.nonConflictingLocalMutations.first().model.page, "Non-conflicting local mutation should be for page 30")
    }
} 