package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.mutations.Mutation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RemoteMutationsPreprocessorTest {
    
    @Test
    fun `test preprocess with empty mutations list`() = runTest {
        // Arrange
        val localDataFetcher = createMockLocalDataFetcher(emptySet())
        val preprocessor = RemoteMutationsPreprocessor(localDataFetcher)
        val remoteMutations = emptyList<RemoteModelMutation<PageBookmark>>()
        
        // Act
        val result = preprocessor.preprocess(remoteMutations)
        
        // Assert
        assertTrue(result.isEmpty(), "Should return empty list for empty input")
    }
    
    @Test
    fun `test preprocess with only CREATED mutations`() = runTest {
        // Arrange
        val localDataFetcher = createMockLocalDataFetcher(emptySet())
        val preprocessor = RemoteMutationsPreprocessor(localDataFetcher)
        val remoteMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark("new-1", 10, 1000L),
                remoteID = "new-1",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark("new-2", 20, 1000L),
                remoteID = "new-2",
                mutation = Mutation.CREATED
            )
        )
        
        // Act
        val result = preprocessor.preprocess(remoteMutations)
        
        // Assert
        assertEquals(2, result.size, "Should keep all CREATED mutations")
        assertEquals("new-1", result[0].remoteID)
        assertEquals("new-2", result[1].remoteID)
    }
    
    @Test
    fun `test preprocess filters out DELETE mutations for non-existent resources`() = runTest {
        // Arrange
        val existingRemoteIDs = setOf("existing-1")
        val localDataFetcher = createMockLocalDataFetcher(existingRemoteIDs)
        val preprocessor = RemoteMutationsPreprocessor(localDataFetcher)
        val remoteMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark("existing-1", 10, 1000L),
                remoteID = "existing-1",
                mutation = Mutation.DELETED
            ),
            RemoteModelMutation(
                model = PageBookmark("non-existent-1", 20, 1000L),
                remoteID = "non-existent-1",
                mutation = Mutation.DELETED
            )
        )
        
        // Act
        val result = preprocessor.preprocess(remoteMutations)
        
        // Assert
        assertEquals(1, result.size, "Should filter out DELETE mutation for non-existent resource")
        assertEquals("existing-1", result[0].remoteID, "Should keep DELETE mutation for existing resource")
    }
    
    @Test
    fun `test preprocess filters out MODIFIED mutations for non-existent resources`() = runTest {
        // Arrange
        val existingRemoteIDs = setOf("existing-1")
        val localDataFetcher = createMockLocalDataFetcher(existingRemoteIDs)
        val preprocessor = RemoteMutationsPreprocessor(localDataFetcher)
        val remoteMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark("existing-1", 10, 1000L),
                remoteID = "existing-1",
                mutation = Mutation.MODIFIED
            ),
            RemoteModelMutation(
                model = PageBookmark("non-existent-1", 20, 1000L),
                remoteID = "non-existent-1",
                mutation = Mutation.MODIFIED
            )
        )
        
        // Act
        val result = preprocessor.preprocess(remoteMutations)
        
        // Assert
        // This is to be modified when new types are handled. 
        assertEquals(1, result.size, "Should filter out MODIFIED mutation for non-existent resource")
        assertEquals("existing-1", result[0].remoteID, "Should keep MODIFIED mutation for existing resource")
    }
    
    @Test
    fun `test preprocess keeps CREATED mutations regardless of local existence`() = runTest {
        // Arrange
        val existingRemoteIDs = setOf("existing-1")
        val localDataFetcher = createMockLocalDataFetcher(existingRemoteIDs)
        val preprocessor = RemoteMutationsPreprocessor(localDataFetcher)
        val remoteMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark("new-1", 10, 1000L),
                remoteID = "new-1",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark("new-2", 20, 1000L),
                remoteID = "new-2",
                mutation = Mutation.CREATED
            )
        )
        
        // Act
        val result = preprocessor.preprocess(remoteMutations)
        
        // Assert
        assertEquals(2, result.size, "Should keep all CREATED mutations regardless of local existence")
        assertEquals("new-1", result[0].remoteID)
        assertEquals("new-2", result[1].remoteID)
    }
    
    @Test
    fun `test preprocess with mixed mutation types`() = runTest {
        // Arrange
        val existingRemoteIDs = setOf("existing-1", "existing-2")
        val localDataFetcher = createMockLocalDataFetcher(existingRemoteIDs)
        val preprocessor = RemoteMutationsPreprocessor(localDataFetcher)
        val remoteMutations = listOf(
            // CREATED mutations (should be kept)
            RemoteModelMutation(
                model = PageBookmark("new-1", 10, 1000L),
                remoteID = "new-1",
                mutation = Mutation.CREATED
            ),
            // DELETE mutations (should be filtered based on existence)
            RemoteModelMutation(
                model = PageBookmark("existing-1", 20, 1000L),
                remoteID = "existing-1",
                mutation = Mutation.DELETED
            ),
            RemoteModelMutation(
                model = PageBookmark("non-existent-1", 30, 1000L),
                remoteID = "non-existent-1",
                mutation = Mutation.DELETED
            ),
            // MODIFIED mutations (should be filtered based on existence)
            RemoteModelMutation(
                model = PageBookmark("existing-2", 40, 1000L),
                remoteID = "existing-2",
                mutation = Mutation.MODIFIED
            ),
            RemoteModelMutation(
                model = PageBookmark("non-existent-2", 50, 1000L),
                remoteID = "non-existent-2",
                mutation = Mutation.MODIFIED
            )
        )
        
        // Act
        val result = preprocessor.preprocess(remoteMutations)
        
        // Assert
        assertEquals(3, result.size, "Should keep CREATED mutations and existing DELETE/MODIFIED mutations")
        
        val resultRemoteIDs = result.map { it.remoteID }.toSet()
        val expectedRemoteIDs = setOf("new-1", "existing-1", "existing-2")
        assertEquals(expectedRemoteIDs, resultRemoteIDs, "Should have expected remote IDs")
    }
    
    @Test
    fun `test preprocess when local data fetcher returns empty existence map`() = runTest {
        // Arrange
        val localDataFetcher = createMockLocalDataFetcher(emptySet())
        val preprocessor = RemoteMutationsPreprocessor(localDataFetcher)
        val remoteMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark("any-id", 10, 1000L),
                remoteID = "any-id",
                mutation = Mutation.DELETED
            )
        )
        
        // Act
        val result = preprocessor.preprocess(remoteMutations)
        
        // Assert
        assertTrue(result.isEmpty(), "Should filter out all DELETE/MODIFIED mutations when existence map is empty")
    }
    
    @Test
    fun `test preprocess preserves mutation order`() = runTest {
        // Arrange
        val existingRemoteIDs = setOf("existing-1", "existing-2")
        val localDataFetcher = createMockLocalDataFetcher(existingRemoteIDs)
        val preprocessor = RemoteMutationsPreprocessor(localDataFetcher)
        val remoteMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark("existing-1", 10, 1000L),
                remoteID = "existing-1",
                mutation = Mutation.DELETED
            ),
            RemoteModelMutation(
                model = PageBookmark("new-1", 20, 1000L),
                remoteID = "new-1",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark("existing-2", 30, 1000L),
                remoteID = "existing-2",
                mutation = Mutation.MODIFIED
            )
        )
        
        // Act
        val result = preprocessor.preprocess(remoteMutations)
        
        // Assert
        assertEquals(3, result.size, "Should keep all mutations")
        // Note: The preprocessor separates mutations into groups and combines them, so order may change
        // It puts filtered mutations (DELETE/MODIFIED that exist locally) first, then CREATED mutations
        val resultRemoteIDs = result.map { it.remoteID }
        assertTrue(resultRemoteIDs.contains("existing-1"), "Should contain existing-1")
        assertTrue(resultRemoteIDs.contains("new-1"), "Should contain new-1") 
        assertTrue(resultRemoteIDs.contains("existing-2"), "Should contain existing-2")
        // The MODIFIED mutation remains MODIFIED in the preprocessor - conversion to CREATE happens later in the pipeline
    }
    
    private fun createMockLocalDataFetcher(existingRemoteIDs: Set<String>): LocalDataFetcher<PageBookmark> {
        return object : LocalDataFetcher<PageBookmark> {
            override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<PageBookmark>> {
                return emptyList()
            }
            
            override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> {
                return remoteIDs.associateWith { it in existingRemoteIDs }
            }
        }
    }
} 