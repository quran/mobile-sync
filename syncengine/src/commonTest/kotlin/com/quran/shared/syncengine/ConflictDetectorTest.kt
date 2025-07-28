package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import kotlin.test.Test
import kotlin.test.assertEquals

class ConflictDetectorTest {
    
    @Test
    fun `getConflicts with empty lists should return empty list`() {
        // Given
        val remoteModelMutations = emptyList<RemoteModelMutation<PageBookmark>>()
        val localModelMutations = emptyList<LocalModelMutation<PageBookmark>>()
        val conflictDetector = ConflictDetector(remoteModelMutations, localModelMutations)
        
        // When
        val conflicts = conflictDetector.getConflicts()
        
        // Then
        assertEquals(0, conflicts.size, "Should return empty list when both input lists are empty")
    }
} 