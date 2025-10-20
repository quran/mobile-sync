@file:OptIn(kotlin.time.ExperimentalTime::class)
package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class PageBookmarksSynchronizationExecutorTest {
    
    private val pipeline = PageBookmarksSynchronizationExecutor()
    
    @Test
    fun `test successful synchronization with no conflicts`() = runTest {
        // Given: Remote and local mutations on different pages (no conflicts)
        val remoteMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark(id = "remote1", page = 10, lastModified = Instant.fromEpochSeconds(1000)),
                remoteID = "remote1",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark(id = "remote2", page = 20, lastModified = Instant.fromEpochSeconds(1001)),
                remoteID = "remote2",
                mutation = Mutation.MODIFIED
            ),
            RemoteModelMutation(
                model = PageBookmark(id = "remote3", page = 30, lastModified = Instant.fromEpochSeconds(1002)),
                remoteID = "remote3",
                mutation = Mutation.DELETED
            )
        )
        
        val localMutations = listOf(
            LocalModelMutation(
                model = PageBookmark(id = "local1", page = 15, lastModified = Instant.fromEpochSeconds(1002)),
                remoteID = null,
                localID = "local1",
                mutation = Mutation.CREATED
            ),
            LocalModelMutation(
                model = PageBookmark(id = "local2", page = 25, lastModified = Instant.fromEpochSeconds(1003)),
                remoteID = "remote2",
                localID = "local2",
                mutation = Mutation.MODIFIED
            )
        )
        
        val lastModificationDate = 500L
        val updatedModificationDate = 1500L
        
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
            }
        )
        
        // Then: Verify results
        assertNotNull(result)
        assertEquals(updatedModificationDate, result.lastModificationDate)
        // After preprocessing, MODIFIED mutations are converted to CREATED, so we expect 2 local mutations
        assertEquals(2, result.localMutations.size) // local1, local2 (MODIFIED converted to CREATED)
        
        // Should have 3 remote mutations (the original ones, no conflicts)
        assertEquals(3, result.remoteMutations.size)
        assertTrue(result.remoteMutations.any { it.remoteID == "remote1" && it.mutation == Mutation.CREATED })
        assertTrue(result.remoteMutations.any { it.remoteID == "remote2" && it.mutation == Mutation.CREATED })
        assertTrue(result.remoteMutations.any { it.remoteID == "remote3" && it.mutation == Mutation.DELETED })
    }
    
    @Test
    fun `test multiple conflicts detection`() = runTest {
        // Given: Multiple conflicts between remote and local mutations
        val remoteMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark(id = "remote1", page = 10, lastModified = Instant.fromEpochSeconds(1000)),
                remoteID = "remote1",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark(id = "remote2", page = 20, lastModified = Instant.fromEpochSeconds(1001)),
                remoteID = "remote2",
                mutation = Mutation.MODIFIED
            ),
            RemoteModelMutation(
                model = PageBookmark(id = "remote3", page = 30, lastModified = Instant.fromEpochSeconds(1002)),
                remoteID = "remote3",
                mutation = Mutation.DELETED
            )
        )
        
        val localMutations = listOf(
            // Conflict 1: Same page as remote1
            LocalModelMutation(
                model = PageBookmark(id = "local1", page = 10, lastModified = Instant.fromEpochSeconds(1003)),
                remoteID = null,
                localID = "local1",
                mutation = Mutation.CREATED
            ),
            // Conflict 2: Same page as remote2
            LocalModelMutation(
                model = PageBookmark(id = "local2", page = 20, lastModified = Instant.fromEpochSeconds(1004)),
                remoteID = null,
                localID = "local2",
                mutation = Mutation.MODIFIED
            ),
            // Conflict 3: Local deletion of remote3
            LocalModelMutation(
                model = PageBookmark(id = "local3", page = 30, lastModified = Instant.fromEpochSeconds(1005)),
                remoteID = "remote3",
                localID = "local3",
                mutation = Mutation.DELETED
            ),
            // No conflict
            LocalModelMutation(
                model = PageBookmark(id = "local4", page = 40, lastModified = Instant.fromEpochSeconds(1006)),
                remoteID = null,
                localID = "local4",
                mutation = Mutation.CREATED
            )
        )
        
        val lastModificationDate = 500L
        val updatedModificationDate = 1500L
        
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
            }
        )
        
        // Then: Verify results
        assertNotNull(result)
        assertEquals(updatedModificationDate, result.lastModificationDate)
        // After preprocessing, MODIFIED mutations are converted to CREATED, so we expect 4 local mutations
        assertEquals(4, result.localMutations.size) // local1, local2 (MODIFIED converted to CREATED), local3, local4
        
        // Should have remote mutations (conflicts are resolved)
        assertTrue(result.remoteMutations.isNotEmpty(), "Should have remote mutations after conflict resolution")
    }
    
    @Test
    fun `test illogical scenario - too many mutations for same page`() = runTest {
        val localMutations = listOf(
            // 3 mutations for the same page
            LocalModelMutation(
                model = PageBookmark(id = "local1", page = 10, lastModified = Instant.fromEpochSeconds(1000)),
                remoteID = null,
                localID = "local1",
                mutation = Mutation.CREATED
            ),
            LocalModelMutation(
                model = PageBookmark(id = "local2", page = 10, lastModified = Instant.fromEpochSeconds(1001)),
                remoteID = null,
                localID = "local2",
                mutation = Mutation.CREATED
            ),
            LocalModelMutation(
                model = PageBookmark(id = "local3", page = 10, lastModified = Instant.fromEpochSeconds(1002)),
                remoteID = "remote1",
                localID = "local3",
                mutation = Mutation.DELETED
            )
        )
        
        val lastModificationDate = 500L
        val updatedModificationDate = 1500L
        
        // When & Then: Execute pipeline should throw exception
        val exception = assertFailsWith<IllegalArgumentException> {
            pipeline.executePipeline(
                fetchLocal = {
                    PageBookmarksSynchronizationExecutor.PipelineInitData(lastModificationDate, localMutations)
                },
                fetchRemote = { _ ->
                    PageBookmarksSynchronizationExecutor.FetchedRemoteData(emptyList(), updatedModificationDate)
                },
                checkLocalExistence = { remoteIDs ->
                    remoteIDs.associateWith { true }
                },
                pushLocal = { mutations, _ ->
                    PageBookmarksSynchronizationExecutor.PushResultData(emptyList(), updatedModificationDate)
                }
            )
        }
        
        assertTrue(exception.message?.contains("Illogical scenario detected") == true,
            "Error message should include illogical scenario details")
        assertTrue(exception.message?.contains("Page 10 has 3 mutations") == true,
            "Error message should include page and mutation count details")
    }
    
    @Test
    fun `test illogical scenario - deletion without remote ID`() = runTest {
        val localMutations = listOf(
            LocalModelMutation(
                model = PageBookmark(id = "local1", page = 10, lastModified = Instant.fromEpochSeconds(1000)),
                remoteID = null, // This should cause an error
                localID = "local1",
                mutation = Mutation.DELETED
            )
        )
        
        val lastModificationDate = 500L
        val updatedModificationDate = 1500L
        
        // When & Then: Execute pipeline should throw exception
        val exception = assertFailsWith<IllegalArgumentException> {
            pipeline.executePipeline(
                fetchLocal = {
                    PageBookmarksSynchronizationExecutor.PipelineInitData(lastModificationDate, localMutations)
                },
                fetchRemote = { _ ->
                    PageBookmarksSynchronizationExecutor.FetchedRemoteData(emptyList(), updatedModificationDate)
                },
                checkLocalExistence = { remoteIDs ->
                    remoteIDs.associateWith { true }
                },
                pushLocal = { mutations, _ ->
                    PageBookmarksSynchronizationExecutor.PushResultData(emptyList(), updatedModificationDate)
                }
            )
        }
        
        assertTrue(exception.message?.contains("deletion without remote ID") == true,
            "Error message should include deletion without remote ID details")
    }
    
    @Test
    fun `test pipeline filters out DELETE mutations for non-existent resources`() = runTest {
        // Given: Remote mutations including DELETE for non-existent resource
        val remoteMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark(id = "remote1", page = 10, lastModified = Instant.fromEpochSeconds(1000)),
                remoteID = "remote1",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark(id = "remote2", page = 20, lastModified = Instant.fromEpochSeconds(1001)),
                remoteID = "remote2",
                mutation = Mutation.DELETED  // This should be filtered out if it doesn't exist locally
            )
        )
        
        val localMutations = emptyList<LocalModelMutation<PageBookmark>>()
        val lastModificationDate = 500L
        val updatedModificationDate = 1500L
        
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
            }
        )
        
        // Then: Verify only the CREATED mutation remains (DELETE was filtered out)
        assertNotNull(result)
        assertEquals(1, result.remoteMutations.size)
        val remainingMutation = result.remoteMutations.first()
        assertEquals("remote1", remainingMutation.remoteID)
        assertEquals(Mutation.CREATED, remainingMutation.mutation)
    }
}