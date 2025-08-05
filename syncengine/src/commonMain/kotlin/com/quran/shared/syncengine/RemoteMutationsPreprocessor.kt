package com.quran.shared.syncengine

import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.mutations.Mutation

/**
 * Preprocesses remote mutations to filter out DELETE and MODIFIED mutations
 * for resources that don't exist locally.
 */
class RemoteMutationsPreprocessor<Model>(
    private val localDataFetcher: LocalDataFetcher<Model>
) {
    
    /**
     * Preprocesses remote mutations to filter out DELETE and MODIFIED mutations
     * for resources that don't exist locally.
     * 
     * @param remoteMutations List of remote mutations to preprocess
     * @return Filtered list of remote mutations
     */
    suspend fun preprocess(remoteMutations: List<RemoteModelMutation<Model>>): List<RemoteModelMutation<Model>> {
        // Filter mutations that need local existence check (DELETE and MODIFIED)
        val mutationsToCheck = remoteMutations.filter { it.mutation == Mutation.DELETED || it.mutation == Mutation.MODIFIED }
        
        if (mutationsToCheck.isEmpty()) {
            return remoteMutations
        }

        // Get remote IDs that need to be checked
        val remoteIDsToCheck = mutationsToCheck.map { it.remoteID }
        
        // Check local existence for these remote IDs
        val existenceMap = localDataFetcher.checkLocalExistence(remoteIDsToCheck)
        
        // Filter out mutations for resources that don't exist locally
        val filteredMutations = mutationsToCheck.filter { mutation ->
            val exists = existenceMap[mutation.remoteID] ?: false
            exists
        }
        
        // Combine filtered mutations with mutations that don't need checking (CREATED)
        val mutationsNotNeedingCheck = remoteMutations.filter { it.mutation == Mutation.CREATED }
        
        return filteredMutations + mutationsNotNeedingCheck
    }
} 