package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PageBookmarksSynchronizationExecutorTest {
    
    private val pipeline = PageBookmarksSynchronizationExecutor()
    
    @Test
    fun `test pipeline with remote and local mutations but no conflicts`() = runTest {
        // Given: Remote and local mutations on different pages (no conflicts)
        val remoteMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark(id = "remote1", page = 10, lastModified = 1000L),
                remoteID = "remote1",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark(id = "remote2", page = 20, lastModified = 1001L),
                remoteID = "remote2",
                mutation = Mutation.DELETED
            )
        )
        
        val localMutations = listOf(
            LocalModelMutation(
                model = PageBookmark(id = "local1", page = 30, lastModified = 1002L),
                remoteID = null,
                localID = "local1",
                mutation = Mutation.CREATED
            ),
            LocalModelMutation(
                model = PageBookmark(id = "local2", page = 40, lastModified = 1003L),
                remoteID = "remote2",
                localID = "local2",
                mutation = Mutation.DELETED
            )
        )
        
        val lastModificationDate = 500L
        val updatedModificationDate = 1500L
        
        var deliveredResult: PageBookmarksSynchronizationExecutor.PipelineResult? = null
        
        // When: Execute pipeline
        val result = pipeline.executePipeline(
            fetchLocal = {
                PageBookmarksSynchronizationExecutor.PipelineInitData(lastModificationDate, localMutations)
            },
            fetchRemote = { _ ->
                PageBookmarksSynchronizationExecutor.FetchedRemoteData(remoteMutations, updatedModificationDate)
            },
            checkLocalExistence = { remoteIDs ->
                // Mock existence check - all remote IDs exist
                remoteIDs.associateWith { true }
            },
            pushLocal = { mutations, _ ->
                // Mock push that returns empty response
                PageBookmarksSynchronizationExecutor.PushResultData(emptyList(), updatedModificationDate)
            },
            deliverResult = { pipelineResult ->
                deliveredResult = pipelineResult
            }
        )
        
        // Then: Verify results
        assertNotNull(result)
        assertEquals(updatedModificationDate, result.lastModificationDate)
        assertEquals(localMutations, result.localMutations)
        
        // Should have 2 remote mutations (the original ones, no conflicts)
        assertEquals(2, result.remoteMutations.size)
        assertTrue(result.remoteMutations.any { it.remoteID == "remote1" && it.mutation == Mutation.CREATED })
        assertTrue(result.remoteMutations.any { it.remoteID == "remote2" && it.mutation == Mutation.DELETED })
        
        // Verify deliverResult was called
        assertNotNull(deliveredResult)
        assertEquals(result, deliveredResult)
    }
    
    @Test
    fun `test pipeline with single conflict`() = runTest {
        // Given: Remote and local mutations on the same page (conflict)
        val remoteMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark(id = "remote1", page = 10, lastModified = 1000L),
                remoteID = "remote1",
                mutation = Mutation.CREATED
            )
        )
        
        val localMutations = listOf(
            LocalModelMutation(
                model = PageBookmark(id = "local1", page = 10, lastModified = 1001L), // Same page as remote
                remoteID = null,
                localID = "local1",
                mutation = Mutation.CREATED
            )
        )
        
        val lastModificationDate = 500L
        val updatedModificationDate = 1500L
        
        var deliveredResult: PageBookmarksSynchronizationExecutor.PipelineResult? = null
        
        // When: Execute pipeline
        val result = pipeline.executePipeline(
            fetchLocal = {
                PageBookmarksSynchronizationExecutor.PipelineInitData(lastModificationDate, localMutations)
            },
            fetchRemote = { _ ->
                PageBookmarksSynchronizationExecutor.FetchedRemoteData(remoteMutations, updatedModificationDate)
            },
            checkLocalExistence = { remoteIDs ->
                // Mock existence check - all remote IDs exist
                remoteIDs.associateWith { true }
            },
            pushLocal = { mutations, _ ->
                // Mock push that returns empty response
                PageBookmarksSynchronizationExecutor.PushResultData(emptyList(), updatedModificationDate)
            },
            deliverResult = { pipelineResult ->
                deliveredResult = pipelineResult
            }
        )
        
        // Then: Verify results
        assertNotNull(result)
        assertEquals(updatedModificationDate, result.lastModificationDate)
        assertEquals(localMutations, result.localMutations)
        
        // Should have 1 remote mutation (the conflicting one gets resolved)
        assertEquals(1, result.remoteMutations.size)
        assertTrue(result.remoteMutations.any { it.remoteID == "remote1" && it.mutation == Mutation.CREATED })
        
        // Verify deliverResult was called
        assertNotNull(deliveredResult)
        assertEquals(result, deliveredResult)
    }

    // TODO: Should merge more cases into less test functions
    @Test
    fun `test pipeline converts UPDATE mutations to CREATE mutations`() = runTest {
        // Given: Remote mutations with UPDATE type
        val remoteMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark(id = "remote1", page = 10, lastModified = 1000L),
                remoteID = "remote1",
                mutation = Mutation.MODIFIED  // This should be converted to CREATE
            )
        )

        val localMutations = emptyList<LocalModelMutation<PageBookmark>>()
        val lastModificationDate = 500L
        val updatedModificationDate = 1500L

        var deliveredResult: PageBookmarksSynchronizationExecutor.PipelineResult? = null

        // When: Execute pipeline
        val result = pipeline.executePipeline(
            fetchLocal = {
                PageBookmarksSynchronizationExecutor.PipelineInitData(lastModificationDate, localMutations)
            },
            fetchRemote = { _ ->
                PageBookmarksSynchronizationExecutor.FetchedRemoteData(remoteMutations, updatedModificationDate)
            },
            checkLocalExistence = { remoteIDs ->
                // Mock existence check - all remote IDs exist
                remoteIDs.associateWith { true }
            },
            pushLocal = { mutations, _ ->
                PageBookmarksSynchronizationExecutor.PushResultData(emptyList(), updatedModificationDate)
            },
            deliverResult = { pipelineResult ->
                deliveredResult = pipelineResult
            }
        )

        // Then: Verify UPDATE was converted to CREATE
        assertNotNull(result)
        assertEquals(1, result.remoteMutations.size)
        val convertedMutation = result.remoteMutations.first()
        assertEquals("remote1", convertedMutation.remoteID)
        assertEquals(Mutation.CREATED, convertedMutation.mutation) // Should be converted from MODIFIED

        // Verify deliverResult was called
        assertNotNull(deliveredResult)
        assertEquals(result, deliveredResult)
    }
    
    @Test
    fun `test pipeline filters out DELETE mutations for non-existent resources`() = runTest {
        // Given: Remote mutations including DELETE for non-existent resource
        val remoteMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark(id = "remote1", page = 10, lastModified = 1000L),
                remoteID = "remote1",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark(id = "remote2", page = 20, lastModified = 1001L),
                remoteID = "remote2",
                mutation = Mutation.DELETED  // This should be filtered out if it doesn't exist locally
            )
        )
        
        val localMutations = emptyList<LocalModelMutation<PageBookmark>>()
        val lastModificationDate = 500L
        val updatedModificationDate = 1500L
        
        var deliveredResult: PageBookmarksSynchronizationExecutor.PipelineResult? = null
        
        // When: Execute pipeline with existence check that says remote2 doesn't exist
        val result = pipeline.executePipeline(
            fetchLocal = {
                PageBookmarksSynchronizationExecutor.PipelineInitData(lastModificationDate, localMutations)
            },
            fetchRemote = { _ ->
                PageBookmarksSynchronizationExecutor.FetchedRemoteData(remoteMutations, updatedModificationDate)
            },
            checkLocalExistence = { remoteIDs ->
                // Mock existence check - only remote1 exists, remote2 doesn't
                mapOf("remote1" to true, "remote2" to false)
            },
            pushLocal = { mutations, _ ->
                PageBookmarksSynchronizationExecutor.PushResultData(emptyList(), updatedModificationDate)
            },
            deliverResult = { pipelineResult ->
                deliveredResult = pipelineResult
            }
        )
        
        // Then: Verify only the CREATED mutation remains (DELETE was filtered out)
        assertNotNull(result)
        assertEquals(1, result.remoteMutations.size)
        val remainingMutation = result.remoteMutations.first()
        assertEquals("remote1", remainingMutation.remoteID)
        assertEquals(Mutation.CREATED, remainingMutation.mutation)
        
        // Verify deliverResult was called
        assertNotNull(deliveredResult)
        assertEquals(result, deliveredResult)
    }
} 