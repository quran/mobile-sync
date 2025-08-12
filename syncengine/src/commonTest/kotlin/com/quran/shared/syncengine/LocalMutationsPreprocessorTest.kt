package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class LocalMutationsPreprocessorTest {
    
    private val preprocessor = LocalMutationsPreprocessor<PageBookmark>()
    
    @Test
    fun `should return empty list when no mutations provided`() {
        val result = preprocessor.preprocess(emptyList()) { it.page }
        assertTrue(result.isEmpty())
    }
    
    @Test
    fun `should return single mutation unchanged`() {
        val mutation = createLocalMutation(1, Mutation.CREATED)
        val result = preprocessor.preprocess(listOf(mutation)) { it.page }
        
        assertEquals(1, result.size)
        assertEquals(mutation, result[0])
    }
    
    @Test
    fun `should return two mutations for same page unchanged`() {
        val mutation1 = createLocalMutation(1, Mutation.CREATED)
        val mutation2 = createLocalMutation(1, Mutation.MODIFIED)
        val result = preprocessor.preprocess(listOf(mutation1, mutation2)) { it.page }
        
        assertEquals(2, result.size)
        assertTrue(result.contains(mutation1))
        assertTrue(result.contains(mutation2))
    }
    
    @Test
    fun `should throw error when more than two mutations for same page`() {
        val mutation1 = createLocalMutation(1, Mutation.CREATED)
        val mutation2 = createLocalMutation(1, Mutation.MODIFIED)
        val mutation3 = createLocalMutation(1, Mutation.DELETED)
        val mutation4 = createLocalMutation(1, Mutation.CREATED)
        
        val exception = assertFailsWith<IllegalArgumentException> {
            preprocessor.preprocess(listOf(mutation1, mutation2, mutation3, mutation4)) { it.page }
        }
        
        assertTrue(exception.message?.contains("Page 1 has 4 mutations") == true)
        assertTrue(exception.message?.contains("exceeds logical limit of 2") == true)
    }
    
    @Test
    fun `should handle multiple pages with valid mutation counts`() {
        // Page 1: 2 mutations (valid)
        val page1Mutation1 = createLocalMutation(1, Mutation.CREATED)
        val page1Mutation2 = createLocalMutation(1, Mutation.MODIFIED)
        
        // Page 2: 1 mutation (valid)
        val page2Mutation = createLocalMutation(2, Mutation.CREATED)
        
        // Page 3: 2 mutations (valid)
        val page3Mutation1 = createLocalMutation(3, Mutation.CREATED)
        val page3Mutation2 = createLocalMutation(3, Mutation.MODIFIED)
        
        val allMutations = listOf(
            page1Mutation1, page1Mutation2,
            page2Mutation,
            page3Mutation1, page3Mutation2
        )
        
        val result = preprocessor.preprocess(allMutations) { it.page }
        
        assertEquals(5, result.size) // 2 + 1 + 2 = 5
        
        val pages = result.map { it.model.page }.sorted()
        assertEquals(listOf(1, 1, 2, 3, 3), pages)
    }
    
    @Test
    fun `should throw error when any page has more than two mutations`() {
        val page1Mutation1 = createLocalMutation(1, Mutation.CREATED)
        val page1Mutation2 = createLocalMutation(1, Mutation.MODIFIED)
        val page1Mutation3 = createLocalMutation(1, Mutation.DELETED) // This makes page 1 have 3 mutations
        
        val page2Mutation1 = createLocalMutation(2, Mutation.CREATED)
        val page2Mutation2 = createLocalMutation(2, Mutation.MODIFIED)
        
        val allMutations = listOf(
            page1Mutation1, page1Mutation2, page1Mutation3,
            page2Mutation1, page2Mutation2
        )
        
        val exception = assertFailsWith<IllegalArgumentException> {
            preprocessor.preprocess(allMutations) { it.page }
        }
        
        assertTrue(exception.message?.contains("Page 1 has 3 mutations") == true)
    }
    
    @Test
    fun `should handle mutations for different pages independently when all are valid`() {
        val page1Mutation1 = createLocalMutation(1, Mutation.CREATED)
        val page1Mutation2 = createLocalMutation(1, Mutation.MODIFIED)
        
        val page2Mutation1 = createLocalMutation(2, Mutation.CREATED)
        val page2Mutation2 = createLocalMutation(2, Mutation.MODIFIED)
        
        val allMutations = listOf(
            page1Mutation1, page1Mutation2,
            page2Mutation1, page2Mutation2
        )
        
        val result = preprocessor.preprocess(allMutations) { it.page }
        
        assertEquals(4, result.size) // 2 + 2 = 4
        
        val pages = result.map { it.model.page }.sorted()
        assertEquals(listOf(1, 1, 2, 2), pages)
    }
    
    @Test
    fun `should throw error when there are two deletions for same page`() {
        val deletion1 = createLocalMutationWithRemoteID(1, Mutation.DELETED, "remote1")
        val deletion2 = createLocalMutationWithRemoteID(1, Mutation.DELETED, "remote2")
        
        val exception = assertFailsWith<IllegalArgumentException> {
            preprocessor.preprocess(listOf(deletion1, deletion2)) { it.page }
        }
        
        assertTrue(exception.message?.contains("Page 1 has 2 deletions") == true)
        assertTrue(exception.message?.contains("which is not allowed") == true)
    }
    
    @Test
    fun `should throw error when there are two creations for same page`() {
        val creation1 = createLocalMutation(1, Mutation.CREATED)
        val creation2 = createLocalMutation(1, Mutation.CREATED)
        
        val exception = assertFailsWith<IllegalArgumentException> {
            preprocessor.preprocess(listOf(creation1, creation2)) { it.page }
        }
        
        assertTrue(exception.message?.contains("Page 1 has 2 creations") == true)
        assertTrue(exception.message?.contains("which is not allowed") == true)
    }
    
    @Test
    fun `should throw error when creation followed by deletion`() {
        val creation = createLocalMutation(1, Mutation.CREATED)
        val deletion = createLocalMutationWithRemoteID(1, Mutation.DELETED, "remote123")
        
        val exception = assertFailsWith<IllegalArgumentException> {
            preprocessor.preprocess(listOf(creation, deletion)) { it.page }
        }
        
        assertTrue(exception.message?.contains("creation followed by deletion") == true)
        assertTrue(exception.message?.contains("two bookmarks on the same page") == true)
    }
    
    @Test
    fun `should throw error when deletion has null remoteID`() {
        val deletion = createLocalMutationWithRemoteID(1, Mutation.DELETED, null)
        
        val exception = assertFailsWith<IllegalArgumentException> {
            preprocessor.preprocess(listOf(deletion)) { it.page }
        }
        
        assertTrue(exception.message?.contains("deletion without remote ID") == true)
        assertTrue(exception.message?.contains("must reference an existing remote resource") == true)
    }
    
    @Test
    fun `should allow deletion followed by creation`() {
        val deletion = createLocalMutationWithRemoteID(1, Mutation.DELETED, "remote123")
        val creation = createLocalMutation(1, Mutation.CREATED)
        
        val result = preprocessor.preprocess(listOf(deletion, creation)) { it.page }
        
        assertEquals(2, result.size)
        assertEquals(deletion, result[0])
        assertEquals(creation, result[1])
    }
    
    @Test
    fun `should allow single creation`() {
        val creation = createLocalMutation(1, Mutation.CREATED)
        
        val result = preprocessor.preprocess(listOf(creation)) { it.page }
        
        assertEquals(1, result.size)
        assertEquals(creation, result[0])
    }
    
    @Test
    fun `should allow single deletion`() {
        val deletion = createLocalMutationWithRemoteID(1, Mutation.DELETED, "remote123")
        
        val result = preprocessor.preprocess(listOf(deletion)) { it.page }
        
        assertEquals(1, result.size)
        assertEquals(deletion, result[0])
    }
    
    @Test
    fun `should allow creation and modification`() {
        val creation = createLocalMutation(1, Mutation.CREATED)
        val modification = createLocalMutation(1, Mutation.MODIFIED)
        
        val result = preprocessor.preprocess(listOf(creation, modification)) { it.page }
        
        assertEquals(2, result.size)
        assertEquals(creation, result[0])
        assertEquals(modification, result[1])
    }
    
    @Test
    fun `should allow deletion and modification`() {
        val deletion = createLocalMutationWithRemoteID(1, Mutation.DELETED, "remote123")
        val modification = createLocalMutation(1, Mutation.MODIFIED)
        
        val result = preprocessor.preprocess(listOf(deletion, modification)) { it.page }
        
        assertEquals(2, result.size)
        assertEquals(deletion, result[0])
        assertEquals(modification, result[1])
    }
    
    private fun createLocalMutation(page: Int, mutation: Mutation): LocalModelMutation<PageBookmark> {
        val timestamp = 1000L + page * 100L
        val model = PageBookmark(
            id = "local_${page}_${timestamp}",
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
        val timestamp = 1000L + page * 100L
        val model = PageBookmark(
            id = "local_${page}_${timestamp}",
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