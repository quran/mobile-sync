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
        assertTrue(exception.message?.contains("exceeds the maximum allowed limit of 2") == true)
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
    
    private fun createLocalMutation(page: Int, mutation: Mutation): LocalModelMutation<PageBookmark> {
        val model = PageBookmark(
            id = "local_${page}_${System.currentTimeMillis()}",
            page = page,
            lastModified = System.currentTimeMillis()
        )
        return LocalModelMutation(
            model = model,
            remoteID = null,
            localID = "local_${page}_${System.currentTimeMillis()}",
            mutation = mutation
        )
    }
} 