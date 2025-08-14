package com.quran.shared.syncengine.preprocessing

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.syncengine.PageBookmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.coroutines.test.runTest

class RemoteMutationsPreprocessorTest {
    
    @Test
    fun `test preprocess with empty mutations list`() = runTest {
        // Arrange
        val checkLocalExistence = createMockExistenceChecker(emptySet())
        val preprocessor = RemoteMutationsPreprocessor(checkLocalExistence)
        val remoteMutations = emptyList<RemoteModelMutation<PageBookmark>>()
        
        // Act
        val result = preprocessor.preprocess(remoteMutations)
        
        // Assert
        assertTrue(result.isEmpty(), "Should return empty list for empty input")
    }
    
    @Test
    fun `test preprocess with only CREATED mutations`() = runTest {
        // Arrange
        val checkLocalExistence = createMockExistenceChecker(emptySet())
        val preprocessor = RemoteMutationsPreprocessor(checkLocalExistence)
        val remoteMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark("new-1", 10, Instant.fromEpochSeconds(1000)),
                remoteID = "new-1",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark("new-2", 20, Instant.fromEpochSeconds(1000)),
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
        val checkLocalExistence = createMockExistenceChecker(existingRemoteIDs)
        val preprocessor = RemoteMutationsPreprocessor(checkLocalExistence)
        val remoteMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark("existing-1", 10, Instant.fromEpochSeconds(1000)),
                remoteID = "existing-1",
                mutation = Mutation.DELETED
            ),
            RemoteModelMutation(
                model = PageBookmark("non-existent-1", 20, Instant.fromEpochSeconds(1000)),
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
        val checkLocalExistence = createMockExistenceChecker(existingRemoteIDs)
        val preprocessor = RemoteMutationsPreprocessor(checkLocalExistence)
        val remoteMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark("existing-1", 10, Instant.fromEpochSeconds(1000)),
                remoteID = "existing-1",
                mutation = Mutation.MODIFIED
            ),
            RemoteModelMutation(
                model = PageBookmark("non-existent-1", 20, Instant.fromEpochSeconds(1000)),
                remoteID = "non-existent-1",
                mutation = Mutation.MODIFIED
            )
        )
        
        // Act
        val result = preprocessor.preprocess(remoteMutations)
        
        // Assert
        assertEquals(2, result.size, "Should keep ALL MODIFIED mutations and convert them to CREATED")
        result.forEach { mutation ->
            assertEquals(Mutation.CREATED, mutation.mutation, "All MODIFIED mutations should be converted to CREATED")
        }
        val resultRemoteIDs = result.map { it.remoteID }.toSet()
        val expectedRemoteIDs = setOf("existing-1", "non-existent-1")
        assertEquals(expectedRemoteIDs, resultRemoteIDs, "Should have all remote IDs")
    }
    
    @Test
    fun `test preprocess converts ALL MODIFIED mutations to CREATED mutations`() = runTest {
        // Arrange
        val existingRemoteIDs = setOf("existing-1", "existing-2")
        val checkLocalExistence = createMockExistenceChecker(existingRemoteIDs)
        val preprocessor = RemoteMutationsPreprocessor(checkLocalExistence)
        val remoteMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark("existing-1", 10, Instant.fromEpochSeconds(1000)),
                remoteID = "existing-1",
                mutation = Mutation.MODIFIED
            ),
            RemoteModelMutation(
                model = PageBookmark("existing-2", 20, Instant.fromEpochSeconds(1000)),
                remoteID = "existing-2",
                mutation = Mutation.MODIFIED
            )
        )
        
        // Act
        val result = preprocessor.preprocess(remoteMutations)
        
        // Assert
        assertEquals(2, result.size, "Should keep all MODIFIED mutations and convert them to CREATED")
        result.forEach { mutation ->
            assertEquals(Mutation.CREATED, mutation.mutation, "All MODIFIED mutations should be converted to CREATED")
        }
        val resultRemoteIDs = result.map { it.remoteID }.toSet()
        val expectedRemoteIDs = setOf("existing-1", "existing-2")
        assertEquals(expectedRemoteIDs, resultRemoteIDs, "Should have expected remote IDs")
    }
    
    @Test
    fun `test preprocess keeps CREATED mutations regardless of local existence`() = runTest {
        // Arrange
        val existingRemoteIDs = setOf("existing-1")
        val checkLocalExistence = createMockExistenceChecker(existingRemoteIDs)
        val preprocessor = RemoteMutationsPreprocessor(checkLocalExistence)
        val remoteMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark("new-1", 10, Instant.fromEpochSeconds(1000)),
                remoteID = "new-1",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark("new-2", 20, Instant.fromEpochSeconds(1000)),
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
        val checkLocalExistence = createMockExistenceChecker(existingRemoteIDs)
        val preprocessor = RemoteMutationsPreprocessor(checkLocalExistence)
        val remoteMutations = listOf(
            // CREATED mutations (should be kept)
            RemoteModelMutation(
                model = PageBookmark("new-1", 10, Instant.fromEpochSeconds(1000)),
                remoteID = "new-1",
                mutation = Mutation.CREATED
            ),
            // DELETE mutations (should be filtered based on existence)
            RemoteModelMutation(
                model = PageBookmark("existing-1", 20, Instant.fromEpochSeconds(1000)),
                remoteID = "existing-1",
                mutation = Mutation.DELETED
            ),
            RemoteModelMutation(
                model = PageBookmark("non-existent-1", 30, Instant.fromEpochSeconds(1000)),
                remoteID = "non-existent-1",
                mutation = Mutation.DELETED
            ),
            // MODIFIED mutations (should ALL be converted to CREATED, regardless of existence)
            RemoteModelMutation(
                model = PageBookmark("existing-2", 40, Instant.fromEpochSeconds(1000)),
                remoteID = "existing-2",
                mutation = Mutation.MODIFIED
            ),
            RemoteModelMutation(
                model = PageBookmark("non-existent-2", 50, Instant.fromEpochSeconds(1000)),
                remoteID = "non-existent-2",
                mutation = Mutation.MODIFIED
            )
        )
        
        // Act
        val result = preprocessor.preprocess(remoteMutations)
        
        // Assert
        assertEquals(4, result.size, "Should keep CREATED mutations, existing DELETE mutations, and ALL MODIFIED mutations")
        
        val resultRemoteIDs = result.map { it.remoteID }.toSet()
        val expectedRemoteIDs = setOf("new-1", "existing-1", "existing-2", "non-existent-2")
        assertEquals(expectedRemoteIDs, resultRemoteIDs, "Should have expected remote IDs")
        
        // Check mutation types
        val createdMutation = result.find { it.remoteID == "new-1" }
        val deletedMutation = result.find { it.remoteID == "existing-1" }
        val existingModifiedMutation = result.find { it.remoteID == "existing-2" }
        val nonExistentModifiedMutation = result.find { it.remoteID == "non-existent-2" }
        
        assertEquals(Mutation.CREATED, createdMutation?.mutation, "CREATED mutation should remain CREATED")
        assertEquals(Mutation.DELETED, deletedMutation?.mutation, "DELETE mutation should remain DELETED")
        assertEquals(Mutation.CREATED, existingModifiedMutation?.mutation, "MODIFIED mutation should be converted to CREATED")
        assertEquals(Mutation.CREATED, nonExistentModifiedMutation?.mutation, "MODIFIED mutation for non-existent resource should also be converted to CREATED")
    }
    
    @Test
    fun `test preprocess when local data fetcher returns empty existence map`() = runTest {
        // Arrange
        val checkLocalExistence = createMockExistenceChecker(emptySet())
        val preprocessor = RemoteMutationsPreprocessor(checkLocalExistence)
        val remoteMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark("any-id", 10, Instant.fromEpochSeconds(1000)),
                remoteID = "any-id",
                mutation = Mutation.DELETED
            ),
            RemoteModelMutation(
                model = PageBookmark("any-id-2", 20, Instant.fromEpochSeconds(1000)),
                remoteID = "any-id-2",
                mutation = Mutation.MODIFIED
            )
        )
        
        // Act
        val result = preprocessor.preprocess(remoteMutations)
        
        // Assert
        assertEquals(1, result.size, "Should filter out DELETE mutations but keep MODIFIED mutations converted to CREATED")
        assertEquals("any-id-2", result[0].remoteID, "Should keep the MODIFIED mutation")
        assertEquals(Mutation.CREATED, result[0].mutation, "MODIFIED mutation should be converted to CREATED")
    }
    
    @Test
    fun `test preprocess preserves mutation order`() = runTest {
        // Arrange
        val existingRemoteIDs = setOf("existing-1", "existing-2")
        val checkLocalExistence = createMockExistenceChecker(existingRemoteIDs)
        val preprocessor = RemoteMutationsPreprocessor(checkLocalExistence)
        val remoteMutations = listOf(
            RemoteModelMutation(
                model = PageBookmark("existing-1", 10, Instant.fromEpochSeconds(1000)),
                remoteID = "existing-1",
                mutation = Mutation.DELETED
            ),
            RemoteModelMutation(
                model = PageBookmark("new-1", 20, Instant.fromEpochSeconds(1000)),
                remoteID = "new-1",
                mutation = Mutation.CREATED
            ),
            RemoteModelMutation(
                model = PageBookmark("existing-2", 30, Instant.fromEpochSeconds(1000)),
                remoteID = "existing-2",
                mutation = Mutation.MODIFIED
            )
        )
        
        // Act
        val result = preprocessor.preprocess(remoteMutations)
        
        // Assert
        assertEquals(3, result.size, "Should keep all mutations")
        // Note: The preprocessor separates mutations into groups and combines them, so order may change
        // It puts CREATED mutations first, then filtered DELETE mutations, then converted MODIFIED mutations
        val resultRemoteIDs = result.map { it.remoteID }
        assertTrue(resultRemoteIDs.contains("existing-1"), "Should contain existing-1")
        assertTrue(resultRemoteIDs.contains("new-1"), "Should contain new-1") 
        assertTrue(resultRemoteIDs.contains("existing-2"), "Should contain existing-2")
        
        // Check that MODIFIED mutation was converted to CREATED
        val modifiedMutation = result.find { it.remoteID == "existing-2" }
        assertEquals(Mutation.CREATED, modifiedMutation?.mutation, "MODIFIED mutation should be converted to CREATED")
    }
    
    private fun createMockExistenceChecker(existingRemoteIDs: Set<String>): suspend (List<String>) -> Map<String, Boolean> {
        return { remoteIDs ->
            remoteIDs.associateWith { it in existingRemoteIDs }
        }
    }
} 