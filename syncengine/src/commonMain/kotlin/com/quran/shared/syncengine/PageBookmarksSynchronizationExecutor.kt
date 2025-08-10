package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation

/**
 * Pure business logic executor for page bookmarks synchronization operations.
 * Contains no external dependencies and is fully testable.
 */
class PageBookmarksSynchronizationExecutor {
    
    // Pipeline Step Data Classes
    data class PipelineInitData(
        val lastModificationDate: Long,
        val localMutations: List<LocalModelMutation<PageBookmark>>
    )

    data class FetchedRemoteData(
        val remoteMutations: List<RemoteModelMutation<PageBookmark>>,
        val lastModificationDate: Long
    )

    data class PushResultData(
        val pushedMutations: List<RemoteModelMutation<PageBookmark>>,
        val lastModificationDate: Long
    )

    data class PipelineResult(
        val lastModificationDate: Long,
        val remoteMutations: List<RemoteModelMutation<PageBookmark>>,
        val localMutations: List<LocalModelMutation<PageBookmark>>
    )

    /**
     * Executes the complete synchronization pipeline.
     * 
     * @param fetchLocal Function to fetch local data (last modification date and local mutations)
     * @param fetchRemote Function to fetch remote mutations
     * @param checkLocalExistence Function to check if remote resources exist locally
     * @param pushLocal Function to push local mutations
     * @param deliverResult Function to deliver the final result
     * @return PipelineResult containing the final state
     */
    suspend fun executePipeline(
        fetchLocal: suspend () -> PipelineInitData,
        fetchRemote: suspend (Long) -> FetchedRemoteData,
        checkLocalExistence: suspend (List<String>) -> Map<String, Boolean>,
        pushLocal: suspend (List<LocalModelMutation<PageBookmark>>, Long) -> PushResultData,
        deliverResult: suspend (PipelineResult) -> Unit
    ): PipelineResult {
        
        // Step 1: Initialize - Get last modification date and local mutations
        val pipelineData = fetchLocal()
        
        // Step 2: Preprocess - Filter local mutations to remove bad states
        val preprocessedLocalMutations = preprocessLocalMutations(pipelineData.localMutations)
        
        // Step 3: Fetch - Get remote modifications from server
        val fetchedData = fetchRemote(pipelineData.lastModificationDate)
        
        // Step 4: Transform - Convert UPDATE mutations to CREATE mutations
        val transformedRemoteMutations = transformRemoteMutations(fetchedData.remoteMutations)
        
        // Step 5: Preprocess - Filter remote mutations based on local existence
        val preprocessedRemoteMutations = preprocessRemoteMutations(transformedRemoteMutations, checkLocalExistence)
        
        // Step 6: Detect - Find conflicts between local and remote mutations
        val conflictDetectionResult = detectConflicts(preprocessedRemoteMutations, preprocessedLocalMutations)
        
        // Step 7: Resolve - Resolve detected conflicts
        val conflictResolutionResult = resolveConflicts(conflictDetectionResult.conflictGroups)
        
        // Step 8: Push - Send local mutations to server
        val pushResult = pushLocal(
            conflictDetectionResult.otherLocalMutations + conflictResolutionResult.mutationsToPush,
            fetchedData.lastModificationDate
        )
        
        // Step 9: Transform Pushed - Convert UPDATE mutations in push response
        val transformedPushedMutations = transformRemoteMutations(pushResult.pushedMutations)
        
        // Step 10: Combine - Merge all remote mutations
        val finalRemoteMutations = combineRemoteMutations(
            conflictDetectionResult.otherRemoteMutations,
            conflictResolutionResult.mutationsToPersist,
            transformedPushedMutations
        )
        
        // Step 11: Complete - Create and deliver result
        val result = PipelineResult(
            lastModificationDate = pushResult.lastModificationDate,
            remoteMutations = finalRemoteMutations,
            localMutations = preprocessedLocalMutations
        )
        
        deliverResult(result)
        return result
    }

    // Pure business logic methods (no external dependencies)
    
    private fun preprocessLocalMutations(
        localMutations: List<LocalModelMutation<PageBookmark>>
    ): List<LocalModelMutation<PageBookmark>> {
        val preprocessor = LocalMutationsPreprocessor<PageBookmark>()
        return preprocessor.preprocess(localMutations) { it.page }
    }
    
    private suspend fun preprocessRemoteMutations(
        remoteMutations: List<RemoteModelMutation<PageBookmark>>,
        checkLocalExistence: suspend (List<String>) -> Map<String, Boolean>
    ): List<RemoteModelMutation<PageBookmark>> {
        // Filter mutations that need local existence check (DELETE and MODIFIED)
        val mutationsToCheck = remoteMutations.filter { it.mutation == Mutation.DELETED || it.mutation == Mutation.MODIFIED }
        
        if (mutationsToCheck.isEmpty()) {
            return remoteMutations
        }

        // Get remote IDs that need to be checked
        val remoteIDsToCheck = mutationsToCheck.map { it.remoteID }
        
        // Check local existence for these remote IDs
        val existenceMap = checkLocalExistence(remoteIDsToCheck)
        
        // Filter out mutations for resources that don't exist locally
        val filteredMutations = mutationsToCheck.filter { mutation ->
            val exists = existenceMap[mutation.remoteID] ?: false
            exists
        }
        
        // Combine filtered mutations with mutations that don't need checking (CREATED)
        val mutationsNotNeedingCheck = remoteMutations.filter { it.mutation == Mutation.CREATED }
        
        return filteredMutations + mutationsNotNeedingCheck
    }
    
    private fun transformRemoteMutations(remoteMutations: List<RemoteModelMutation<PageBookmark>>): List<RemoteModelMutation<PageBookmark>> {
        return remoteMutations.map { remoteMutation ->
            if (remoteMutation.mutation == Mutation.MODIFIED) {
                RemoteModelMutation(
                    model = remoteMutation.model,
                    remoteID = remoteMutation.remoteID,
                    mutation = Mutation.CREATED
                )
            } else {
                remoteMutation
            }
        }
    }

    private fun detectConflicts(
        remoteMutations: List<RemoteModelMutation<PageBookmark>>,
        localMutations: List<LocalModelMutation<PageBookmark>>
    ): ConflictDetectionResult<PageBookmark> {
        val conflictDetector = ConflictDetector(remoteMutations, localMutations)
        return conflictDetector.getConflicts()
    }

    private fun resolveConflicts(conflictGroups: List<ConflictGroup<PageBookmark>>): ConflictResolutionResult<PageBookmark> {
        val conflictResolver = ConflictResolver(conflictGroups)
        return conflictResolver.resolve()
    }

    private fun combineRemoteMutations(
        otherRemoteMutations: List<RemoteModelMutation<PageBookmark>>,
        mutationsToPersist: List<RemoteModelMutation<PageBookmark>>,
        pushedMutations: List<RemoteModelMutation<PageBookmark>>
    ): List<RemoteModelMutation<PageBookmark>> {
        return otherRemoteMutations + mutationsToPersist + pushedMutations
    }
} 