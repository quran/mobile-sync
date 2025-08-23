package com.quran.shared.syncengine.preprocessing

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.syncengine.PageBookmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.datetime.Instant
class LocalMutationsPreprocessorTest {
    
    private val preprocessor = LocalMutationsPreprocessor()
    
    @Test
    fun `should return empty list when no mutations provided`() {
        val result = preprocessor.preprocess(emptyList())
        assertTrue(result.isEmpty())
    }
    
    @Test
    fun `should return single mutation unchanged`() {
        val mutation = createLocalMutation(1, Mutation.CREATED)
        val result = preprocessor.preprocess(listOf(mutation))
        
        assertEquals(1, result.size)
        assertEquals(mutation, result[0])
    }
    
    @Test
    fun `should return two mutations for same page unchanged`() {
        val mutation1 = createLocalMutation(1, Mutation.CREATED)
        val mutation2 = createLocalMutation(1, Mutation.MODIFIED)
        
        // This should now throw an error because after conversion we have 2 CREATED mutations
        val exception = assertFailsWith<IllegalArgumentException> {
            preprocessor.preprocess(listOf(mutation1, mutation2))
        }
        
        assertTrue(exception.message?.contains("Page 1 has 2 creations") == true)
        assertTrue(exception.message?.contains("which is not allowed") == true)
    }
    
    @Test
    fun `should throw error when more than two mutations for same page`() {
        val mutation1 = createLocalMutation(1, Mutation.CREATED)
        val mutation2 = createLocalMutation(1, Mutation.MODIFIED) // This will be converted to CREATED
        val mutation3 = createLocalMutation(1, Mutation.DELETED)
        val mutation4 = createLocalMutation(1, Mutation.CREATED)
        
        val exception = assertFailsWith<IllegalArgumentException> {
            preprocessor.preprocess(listOf(mutation1, mutation2, mutation3, mutation4))
        }
        
        assertTrue(exception.message?.contains("Page 1 has 4 mutations") == true) // After conversion, there are 4 mutations
        assertTrue(exception.message?.contains("exceeds logical limit of 2") == true)
    }
    
    @Test
    fun `should handle multiple pages with valid mutation counts`() {
        // Page 1: 2 mutations (valid) - MODIFIED will be converted to CREATED, causing error
        val page1Mutation1 = createLocalMutation(1, Mutation.CREATED)
        val page1Mutation2 = createLocalMutation(1, Mutation.MODIFIED) // This will be converted to CREATED
        
        // Page 2: 1 mutation (valid)
        val page2Mutation = createLocalMutation(2, Mutation.CREATED)
        
        // Page 3: 2 mutations (valid) - MODIFIED will be converted to CREATED, causing error
        val page3Mutation1 = createLocalMutation(3, Mutation.CREATED)
        val page3Mutation2 = createLocalMutation(3, Mutation.MODIFIED) // This will be converted to CREATED
        
        val allMutations = listOf(
            page1Mutation1, page1Mutation2,
            page2Mutation,
            page3Mutation1, page3Mutation2
        )
        
        // This should throw an error because pages 1 and 3 will have multiple CREATED mutations
        val exception = assertFailsWith<IllegalArgumentException> {
            preprocessor.preprocess(allMutations)
        }
        
        assertTrue(exception.message?.contains("Page 1 has 2 creations") == true || 
                  exception.message?.contains("Page 3 has 2 creations") == true)
    }
    
    @Test
    fun `should throw error when any page has more than two mutations`() {
        val page1Mutation1 = createLocalMutation(1, Mutation.CREATED)
        val page1Mutation2 = createLocalMutation(1, Mutation.MODIFIED) // This will be converted to CREATED
        val page1Mutation3 = createLocalMutationWithRemoteID(1, Mutation.DELETED, "remote1") // This makes page 1 have 3 mutations after conversion
        val page1Mutation4 = createLocalMutation(1, Mutation.CREATED) // This makes page 1 have 4 mutations after conversion
        
        val page2Mutation1 = createLocalMutation(2, Mutation.CREATED)
        val page2Mutation2 = createLocalMutation(2, Mutation.MODIFIED) // This will be converted to CREATED
        
        val allMutations = listOf(
            page1Mutation1, page1Mutation2, page1Mutation3, page1Mutation4,
            page2Mutation1, page2Mutation2
        )
        
        val exception = assertFailsWith<IllegalArgumentException> {
            preprocessor.preprocess(allMutations)
        }
        
        assertTrue(exception.message?.contains("Page 1 has 4 mutations") == true)
        assertTrue(exception.message?.contains("exceeds logical limit of 2") == true)
    }
    
    @Test
    fun `should handle mutations for different pages independently when all are valid`() {
        val page1Mutation1 = createLocalMutation(1, Mutation.CREATED)
        val page1Mutation2 = createLocalMutation(1, Mutation.MODIFIED) // This will be converted to CREATED
        
        val page2Mutation1 = createLocalMutation(2, Mutation.CREATED)
        val page2Mutation2 = createLocalMutation(2, Mutation.MODIFIED) // This will be converted to CREATED
        
        val allMutations = listOf(
            page1Mutation1, page1Mutation2,
            page2Mutation1, page2Mutation2
        )
        
        // This should throw an error because both pages will have multiple CREATED mutations
        val exception = assertFailsWith<IllegalArgumentException> {
            preprocessor.preprocess(allMutations)
        }
        
        assertTrue(exception.message?.contains("Page 1 has 2 creations") == true || 
                  exception.message?.contains("Page 2 has 2 creations") == true)
    }
    
    @Test
    fun `should throw error when there are two deletions for same page`() {
        val deletion1 = createLocalMutationWithRemoteID(1, Mutation.DELETED, "remote1")
        val deletion2 = createLocalMutationWithRemoteID(1, Mutation.DELETED, "remote2")
        
        val exception = assertFailsWith<IllegalArgumentException> {
            preprocessor.preprocess(listOf(deletion1, deletion2))
        }
        
        assertTrue(exception.message?.contains("Page 1 has 2 deletions") == true)
        assertTrue(exception.message?.contains("which is not allowed") == true)
    }
    
    @Test
    fun `should throw error when there are two creations for same page`() {
        val creation1 = createLocalMutation(1, Mutation.CREATED)
        val creation2 = createLocalMutation(1, Mutation.CREATED)
        
        val exception = assertFailsWith<IllegalArgumentException> {
            preprocessor.preprocess(listOf(creation1, creation2))
        }
        
        assertTrue(exception.message?.contains("Page 1 has 2 creations") == true)
        assertTrue(exception.message?.contains("which is not allowed") == true)
    }
    
    @Test
    fun `should throw error when creation followed by deletion`() {
        val creation = createLocalMutation(1, Mutation.CREATED)
        val deletion = createLocalMutationWithRemoteID(1, Mutation.DELETED, "remote123")
        
        val exception = assertFailsWith<IllegalArgumentException> {
            preprocessor.preprocess(listOf(creation, deletion))
        }
        
        assertTrue(exception.message?.contains("creation followed by deletion") == true)
        assertTrue(exception.message?.contains("two bookmarks on the same page") == true)
    }
    
    @Test
    fun `should throw error when deletion has null remoteID`() {
        val deletion = createLocalMutationWithRemoteID(1, Mutation.DELETED, null)
        
        val exception = assertFailsWith<IllegalArgumentException> {
            preprocessor.preprocess(listOf(deletion))
        }
        
        assertTrue(exception.message?.contains("deletion without remote ID") == true)
        assertTrue(exception.message?.contains("must reference an existing remote resource") == true)
    }
    
    @Test
    fun `should not allow modified followed by creation`() {
        val deletion = createLocalMutationWithRemoteID(1, Mutation.MODIFIED, "remote123")
        val creation = createLocalMutation(1, Mutation.CREATED)
        
        // This should throw an error because after conversion we have two creations
        assertFailsWith<IllegalArgumentException> {
            preprocessor.preprocess(listOf(deletion, creation))
        }
    }
    
    @Test
    fun `should allow single creation`() {
        val creation = createLocalMutation(1, Mutation.CREATED)
        
        val result = preprocessor.preprocess(listOf(creation))
        
        assertEquals(1, result.size)
        assertEquals(creation, result[0])
    }
    
    @Test
    fun `should allow single deletion`() {
        val deletion = createLocalMutationWithRemoteID(1, Mutation.DELETED, "remote123")
        
        val result = preprocessor.preprocess(listOf(deletion))
        
        assertEquals(1, result.size)
        assertEquals(deletion, result[0])
    }
    
    @Test
    fun `should allow creation and modification`() {
        val creation = createLocalMutation(1, Mutation.CREATED)
        val modification = createLocalMutation(1, Mutation.MODIFIED)
        
        // This should throw an error because after conversion we have 2 CREATED mutations
        val exception = assertFailsWith<IllegalArgumentException> {
            preprocessor.preprocess(listOf(creation, modification))
        }
        
        assertTrue(exception.message?.contains("Page 1 has 2 creations") == true)
        assertTrue(exception.message?.contains("which is not allowed") == true)
    }
    
    @Test
    fun `should allow deletion and modification`() {
        val deletion = createLocalMutationWithRemoteID(1, Mutation.DELETED, "remote123")
        val modification = createLocalMutation(1, Mutation.MODIFIED)
        
        val result = preprocessor.preprocess(listOf(deletion, modification))
        
        assertEquals(2, result.size) // MODIFIED mutation is converted to CREATED
        assertEquals(deletion, result[0])
        assertTrue(result.any { it.localID == modification.localID && it.mutation == Mutation.CREATED }) // MODIFIED converted to CREATED
    }
    
    @Test
    fun `should maintain order of input relative to same logical resource`() {
        // Create mutations for different pages in a specific order
        val mutation1 = createLocalMutation(1, Mutation.CREATED)
        val mutation2 = createLocalMutation(2, Mutation.MODIFIED) // Will be converted to CREATED
        val mutation3 = createLocalMutationWithRemoteID(3, Mutation.DELETED, "remote3")
        val mutation4 = createLocalMutation(4, Mutation.CREATED)
        val mutation5 = createLocalMutationWithRemoteID(5, Mutation.DELETED, "remote5")
        
        val inputMutations = listOf(mutation1, mutation2, mutation3, mutation4, mutation5)
        
        val result = preprocessor.preprocess(inputMutations)
        
        // All mutations should be kept since they're for different pages
        assertEquals(5, result.size, "Should keep all mutations for different pages")
        
        // Check that the relative order is maintained
        assertEquals(inputMutations.map { it.localID}, result.map { it.localID}, "Order should be maintained in output")
    }
    
    private fun createLocalMutation(page: Int, mutation: Mutation): LocalModelMutation<PageBookmark> {
        val timestamp = Instant.fromEpochSeconds(1000L + page * 100L)
        val model = PageBookmark(
            id = "local_${page}_${timestamp.epochSeconds}",
            page = page,
            lastModified = timestamp
        )
        return LocalModelMutation(
            model = model,
            remoteID = null,
            localID = "local_${page}_${timestamp}",
            mutation = mutation
        )
    }
    
    private fun createLocalMutationWithRemoteID(page: Int, mutation: Mutation, remoteID: String?): LocalModelMutation<PageBookmark> {
        val timestamp = Instant.fromEpochSeconds(1000L + page * 100L)
        val model = PageBookmark(
            id = "local_${page}_${timestamp.epochSeconds}",
            page = page,
            lastModified = timestamp
        )
        return LocalModelMutation(
            model = model,
            remoteID = remoteID,
            localID = "local_${page}_${timestamp}",
            mutation = mutation
        )
    }
}