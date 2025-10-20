@file:OptIn(kotlin.time.ExperimentalTime::class)
package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.PageBookmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class ConflictResolverTest {
    
    @Test
    fun `resolve with empty conflict groups should return empty result`() {
        // Given
        val conflictResolver = ConflictResolver(emptyList())
        
        // When
        val result = conflictResolver.resolve()
        
        // Then
        assertEquals(0, result.mutationsToPersist.size, "Number of mutations to persist")
        assertEquals(0, result.mutationsToPush.size, "Number of mutations to push")
    }
    
    @Test
    fun `resolve with single page created locally and remotely should persist remote mutation`() {
        // Given
        val remoteMutation = RemoteModelMutation(
            model = PageBookmark(id = "remote-1", page = 10, lastModified = Instant.fromEpochSeconds(1000)),
            remoteID = "remote-1",
            mutation = Mutation.CREATED
        )
        val localMutation = LocalModelMutation(
            model = PageBookmark(id = "local-1", page = 10, lastModified = Instant.fromEpochSeconds(1001)),
            remoteID = null,
            localID = "local-1",
            mutation = Mutation.CREATED
        )
        val resourceConflict = ResourceConflict(
            localMutations = listOf(localMutation),
            remoteMutations = listOf(remoteMutation)
        )
        val conflictResolver = ConflictResolver(listOf(resourceConflict))
        
        // When
        val result = conflictResolver.resolve()
        
        // Then
        assertEquals(1, result.mutationsToPersist.size, "Number of mutations to persist")
        assertEquals(0, result.mutationsToPush.size, "Number of mutations to push")
        assertEquals(remoteMutation, result.mutationsToPersist.first(), "Persisted mutation should be the remote mutation")
    }
    
    @Test
    fun `resolve with single resource deleted locally and remotely should persist remote mutation`() {
        // Given
        val remoteMutation = RemoteModelMutation(
            model = PageBookmark(id = "remote-1", page = 10, lastModified = Instant.fromEpochSeconds(1000)),
            remoteID = "remote-1",
            mutation = Mutation.DELETED
        )
        val localMutation = LocalModelMutation(
            model = PageBookmark(id = "local-1", page = 10, lastModified = Instant.fromEpochSeconds(1001)),
            remoteID = "remote-1",
            localID = "local-1",
            mutation = Mutation.DELETED
        )
        val resourceConflict = ResourceConflict(
            localMutations = listOf(localMutation),
            remoteMutations = listOf(remoteMutation)
        )
        val conflictResolver = ConflictResolver(listOf(resourceConflict))
        
        // When
        val result = conflictResolver.resolve()
        
        // Then
        assertEquals(1, result.mutationsToPersist.size, "Number of mutations to persist")
        assertEquals(0, result.mutationsToPush.size, "Number of mutations to push")
        assertEquals(remoteMutation, result.mutationsToPersist.first(), "Persisted mutation should be the remote mutation")
    }
    
    @Test
    fun `resolve with remote delete and create vs local delete should persist both remote mutations`() {
        // Given
        val remoteDeleteMutation = RemoteModelMutation(
            model = PageBookmark(id = "remote-1", page = 0, lastModified = Instant.fromEpochSeconds(1000)),
            remoteID = "remote-1",
            mutation = Mutation.DELETED
        )
        val remoteCreateMutation = RemoteModelMutation(
            model = PageBookmark(id = "remote-2", page = 10, lastModified = Instant.fromEpochSeconds(1001)),
            remoteID = "remote-2",
            mutation = Mutation.CREATED
        )
        val localDeleteMutation = LocalModelMutation(
            model = PageBookmark(id = "local-1", page = 10, lastModified = Instant.fromEpochSeconds(1002)),
            remoteID = "remote-1",
            localID = "local-1",
            mutation = Mutation.DELETED
        )
        val resourceConflict = ResourceConflict(
            localMutations = listOf(localDeleteMutation),
            remoteMutations = listOf(remoteDeleteMutation, remoteCreateMutation)
        )
        val conflictResolver = ConflictResolver(listOf(resourceConflict))
        
        // When
        val result = conflictResolver.resolve()
        
        // Then
        assertEquals(2, result.mutationsToPersist.size, "Number of mutations to persist")
        assertEquals(0, result.mutationsToPush.size, "Number of mutations to push")
        assertEquals(remoteDeleteMutation, result.mutationsToPersist[0], "First persisted mutation should be the remote delete")
        assertEquals(remoteCreateMutation, result.mutationsToPersist[1], "Second persisted mutation should be the remote create")
    }
    
    @Test
    fun `resolve with remote delete vs local delete and create should persist remote delete and push local create`() {
        // Given
        val remoteDeleteMutation = RemoteModelMutation(
            model = PageBookmark(id = "remote-1", page = 0, lastModified = Instant.fromEpochSeconds(1000)),
            remoteID = "remote-1",
            mutation = Mutation.DELETED
        )
        val localDeleteMutation = LocalModelMutation(
            model = PageBookmark(id = "local-1", page = 10, lastModified = Instant.fromEpochSeconds(1001)),
            remoteID = "remote-1",
            localID = "local-1",
            mutation = Mutation.DELETED
        )
        val localCreateMutation = LocalModelMutation(
            model = PageBookmark(id = "local-2", page = 10, lastModified = Instant.fromEpochSeconds(1002)),
            remoteID = null,
            localID = "local-2",
            mutation = Mutation.CREATED
        )
        val resourceConflict = ResourceConflict(
            localMutations = listOf(localDeleteMutation, localCreateMutation),
            remoteMutations = listOf(remoteDeleteMutation)
        )
        val conflictResolver = ConflictResolver(listOf(resourceConflict))
        
        // When
        val result = conflictResolver.resolve()
        
        // Then
        assertEquals(1, result.mutationsToPersist.size, "Number of mutations to persist")
        assertEquals(1, result.mutationsToPush.size, "Number of mutations to push")
        assertEquals(remoteDeleteMutation, result.mutationsToPersist.first(), "Persisted mutation should be the remote delete")
        assertEquals(localCreateMutation, result.mutationsToPush.first(), "Pushed mutation should be the local create")
    }
    
    @Test
    fun `resolve with delete and create on both sides should persist remote mutations only`() {
        // Given
        val remoteDeleteMutation = RemoteModelMutation(
            model = PageBookmark(id = "remote-1", page = 0, lastModified = Instant.fromEpochSeconds(1000)),
            remoteID = "remote-1",
            mutation = Mutation.DELETED
        )
        val remoteCreateMutation = RemoteModelMutation(
            model = PageBookmark(id = "remote-2", page = 10, lastModified = Instant.fromEpochSeconds(1001)),
            remoteID = "remote-2",
            mutation = Mutation.CREATED
        )
        val localDeleteMutation = LocalModelMutation(
            model = PageBookmark(id = "local-1", page = 10, lastModified = Instant.fromEpochSeconds(1002)),
            remoteID = "remote-1",
            localID = "local-1",
            mutation = Mutation.DELETED
        )
        val localCreateMutation = LocalModelMutation(
            model = PageBookmark(id = "local-2", page = 10, lastModified = Instant.fromEpochSeconds(1003)),
            remoteID = null,
            localID = "local-2",
            mutation = Mutation.CREATED
        )
        val resourceConflict = ResourceConflict(
            localMutations = listOf(localDeleteMutation, localCreateMutation),
            remoteMutations = listOf(remoteDeleteMutation, remoteCreateMutation)
        )
        val conflictResolver = ConflictResolver(listOf(resourceConflict))
        
        // When
        val result = conflictResolver.resolve()
        
        // Then
        assertEquals(2, result.mutationsToPersist.size, "Number of mutations to persist")
        assertEquals(0, result.mutationsToPush.size, "Number of mutations to push")
        assertEquals(remoteDeleteMutation, result.mutationsToPersist[0], "First persisted mutation should be the remote delete")
        assertEquals(remoteCreateMutation, result.mutationsToPersist[1], "Second persisted mutation should be the remote create")
    }
    
    @Test
    fun `resolve with multiple conflict groups should handle each group independently`() {
        // Given - First conflict group: CREATE vs CREATE on page 10
        val remoteCreate1 = RemoteModelMutation(
            model = PageBookmark(id = "remote-1", page = 10, lastModified = Instant.fromEpochSeconds(1000)),
            remoteID = "remote-1",
            mutation = Mutation.CREATED
        )
        val localCreate1 = LocalModelMutation(
            model = PageBookmark(id = "local-1", page = 10, lastModified = Instant.fromEpochSeconds(1001)),
            remoteID = null,
            localID = "local-1",
            mutation = Mutation.CREATED
        )
        val resourceConflict1 = ResourceConflict(
            localMutations = listOf(localCreate1),
            remoteMutations = listOf(remoteCreate1)
        )
        
        // Given - Second conflict group: DELETE vs DELETE on page 20
        val remoteDelete2 = RemoteModelMutation(
            model = PageBookmark(id = "remote-2", page = 0, lastModified = Instant.fromEpochSeconds(1002)),
            remoteID = "remote-2",
            mutation = Mutation.DELETED
        )
        val localDelete2 = LocalModelMutation(
            model = PageBookmark(id = "local-2", page = 20, lastModified = Instant.fromEpochSeconds(1003)),
            remoteID = "remote-2",
            localID = "local-2",
            mutation = Mutation.DELETED
        )
        val resourceConflict2 = ResourceConflict(
            localMutations = listOf(localDelete2),
            remoteMutations = listOf(remoteDelete2)
        )
        
        // Given - Third conflict group: Remote delete+create vs local delete on page 30
        val remoteDelete3 = RemoteModelMutation(
            model = PageBookmark(id = "remote-3", page = 0, lastModified = Instant.fromEpochSeconds(1004)),
            remoteID = "remote-3",
            mutation = Mutation.DELETED
        )
        val remoteCreate3 = RemoteModelMutation(
            model = PageBookmark(id = "remote-4", page = 30, lastModified = Instant.fromEpochSeconds(1005)),
            remoteID = "remote-4",
            mutation = Mutation.CREATED
        )
        val localDelete3 = LocalModelMutation(
            model = PageBookmark(id = "local-3", page = 30, lastModified = Instant.fromEpochSeconds(1006)),
            remoteID = "remote-3",
            localID = "local-3",
            mutation = Mutation.DELETED
        )
        val resourceConflict3 = ResourceConflict(
            localMutations = listOf(localDelete3),
            remoteMutations = listOf(remoteDelete3, remoteCreate3)
        )
        
        val conflictResolver = ConflictResolver(listOf(resourceConflict1, resourceConflict2, resourceConflict3))
        
        // When
        val result = conflictResolver.resolve()
        
        // Then
        assertEquals(4, result.mutationsToPersist.size, "Number of mutations to persist")
        assertEquals(0, result.mutationsToPush.size, "Number of mutations to push")
        
        // Verify all remote mutations are persisted
        val persistedRemoteIDs = result.mutationsToPersist.map { it.remoteID }.toSet()
        assertEquals(setOf("remote-1", "remote-2", "remote-3", "remote-4"), persistedRemoteIDs, "All remote mutations should be persisted")
    }
    
    @Test
    fun `resolve with local creation vs remote deletion should throw error`() {
        // Given
        val remoteDeleteMutation = RemoteModelMutation(
            model = PageBookmark(id = "remote-1", page = 0, lastModified = Instant.fromEpochSeconds(1000)),
            remoteID = "remote-1",
            mutation = Mutation.DELETED
        )
        val localCreateMutation = LocalModelMutation(
            model = PageBookmark(id = "local-1", page = 10, lastModified = Instant.fromEpochSeconds(1001)),
            remoteID = null,
            localID = "local-1",
            mutation = Mutation.CREATED
        )
        val resourceConflict = ResourceConflict(
            localMutations = listOf(localCreateMutation),
            remoteMutations = listOf(remoteDeleteMutation)
        )
        val conflictResolver = ConflictResolver(listOf(resourceConflict))
        
        // When & Then
        val exception = assertFailsWith<IllegalArgumentException> {
            conflictResolver.resolve()
        }
        
        assertTrue(
            exception.message?.contains("Local creation conflicts with remote deletion") == true,
            "Error message should mention local creation vs remote deletion conflict"
        )
        assertTrue(
            exception.message?.contains("CREATED(local-1)") == true,
            "Error message should include local mutation details"
        )
        assertTrue(
            exception.message?.contains("DELETED(remote-1)") == true,
            "Error message should include remote mutation details"
        )
    }
    
    @Test
    fun `resolve with local deletion vs remote creation should throw error`() {
        // Given
        val remoteCreateMutation = RemoteModelMutation(
            model = PageBookmark(id = "remote-1", page = 10, lastModified = Instant.fromEpochSeconds(1000)),
            remoteID = "remote-1",
            mutation = Mutation.CREATED
        )
        val localDeleteMutation = LocalModelMutation(
            model = PageBookmark(id = "local-1", page = 0, lastModified = Instant.fromEpochSeconds(1001)),
            remoteID = "remote-1",
            localID = "local-1",
            mutation = Mutation.DELETED
        )
        val resourceConflict = ResourceConflict(
            localMutations = listOf(localDeleteMutation),
            remoteMutations = listOf(remoteCreateMutation)
        )
        val conflictResolver = ConflictResolver(listOf(resourceConflict))
        
        // When & Then
        val exception = assertFailsWith<IllegalArgumentException> {
            conflictResolver.resolve()
        }
        
        assertTrue(
            exception.message?.contains("Local deletion conflicts with remote creation") == true,
            "Error message should mention local deletion vs remote creation conflict"
        )
        assertTrue(
            exception.message?.contains("DELETED(local-1)") == true,
            "Error message should include local mutation details"
        )
        assertTrue(
            exception.message?.contains("CREATED(remote-1)") == true,
            "Error message should include remote mutation details"
        )
    }
}