package com.quran.shared.syncengine

import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.mutations.Mutation

class RemoteMutationsPreprocessor(
    private val checkLocalExistence: suspend (List<String>) -> Map<String, Boolean>
) {
    
    /**
     * Preprocesses remote mutations to filter out DELETE mutations for resources that don't exist
     * locally and convert ALL MODIFIED mutations to CREATED mutations.
     *
     * @param remoteMutations List of remote mutations to preprocess
     * @return Filtered and transformed list of remote mutations
     */
    suspend fun preprocess(remoteMutations: List<RemoteModelMutation<PageBookmark>>): List<RemoteModelMutation<PageBookmark>> {
        // Separate mutations by type
        val createdMutations = remoteMutations.filter { it.mutation == Mutation.CREATED }
        val deletedMutations = remoteMutations.filter { it.mutation == Mutation.DELETED }
        val modifiedMutations = remoteMutations.filter { it.mutation == Mutation.MODIFIED }
        
        // Handle DELETE mutations - filter out those for non-existent resources
        val filteredDeletedMutations = if (deletedMutations.isNotEmpty()) {
            val remoteIDsToCheck = deletedMutations.map { it.remoteID }
            val existenceMap = checkLocalExistence(remoteIDsToCheck)
            
            deletedMutations.filter { existenceMap[it.remoteID] ?: false }
        } else {
            emptyList()
        }
        
        // Handle MODIFIED mutations - convert ALL to CREATED (no existence check needed)
        val transformedModifiedMutations = modifiedMutations.map { mutation ->
            RemoteModelMutation(
                model = mutation.model,
                remoteID = mutation.remoteID,
                mutation = Mutation.CREATED
            )
        }
        
        // Combine all mutations
        return createdMutations + filteredDeletedMutations + transformedModifiedMutations
    }
} 