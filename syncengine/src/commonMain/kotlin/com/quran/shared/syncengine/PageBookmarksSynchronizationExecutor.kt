package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.conflict.ConflictDetector
import com.quran.shared.syncengine.conflict.ConflictDetectionResult
import com.quran.shared.syncengine.conflict.ResourceConflict
import com.quran.shared.syncengine.conflict.ConflictResolver
import com.quran.shared.syncengine.conflict.ConflictResolutionResult
import com.quran.shared.syncengine.preprocessing.LocalMutationsPreprocessor
import com.quran.shared.syncengine.preprocessing.RemoteMutationsPreprocessor

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
     * @return PipelineResult containing the final state
     */
    suspend fun executePipeline(
        fetchLocal: suspend () -> PipelineInitData,
        fetchRemote: suspend (Long) -> FetchedRemoteData,
        checkLocalExistence: suspend (List<String>) -> Map<String, Boolean>,
        pushLocal: suspend (List<LocalModelMutation<PageBookmark>>, Long) -> PushResultData
    ): PipelineResult {
        
        // Step 1: Initialize - Get last modification date and local mutations
        val pipelineData = fetchLocal()
        
        // Step 2: Preprocess - Filter local mutations to remove bad states
        val preprocessedLocalMutations = preprocessLocalMutations(pipelineData.localMutations)
        
        // Step 3: Fetch - Get remote modifications from server
        val fetchedData = fetchRemote(pipelineData.lastModificationDate)
        
        // Step 4: Preprocess - Filter and transform remote mutations
        val preprocessedRemoteMutations = preprocessRemoteMutations(fetchedData.remoteMutations, checkLocalExistence)
        
        // Step 6: Detect - Find conflicts between local and remote mutations
        val conflictDetectionResult = detectConflicts(preprocessedRemoteMutations, preprocessedLocalMutations)
        
        // Step 7: Resolve - Resolve detected conflicts
        val conflictResolutionResult = resolveConflicts(conflictDetectionResult.conflicts)
        
        // Step 8: Push - Send local mutations to server
        val pushResult = pushLocal(
            conflictDetectionResult.nonConflictingLocalMutations + conflictResolutionResult.mutationsToPush,
            fetchedData.lastModificationDate
        )
        
        // Step 9: Preprocess Pushed - Filter and transform pushed mutations
        val preprocessedPushedMutations = preprocessRemoteMutations(pushResult.pushedMutations, checkLocalExistence)
        
        // Step 10: Combine - Merge all remote mutations
        val finalRemoteMutations = combineRemoteMutations(
            conflictDetectionResult.nonConflictingRemoteMutations,
            conflictResolutionResult.mutationsToPersist,
            preprocessedPushedMutations
        )
        
        // Step 11: Complete - Create and return result
        return PipelineResult(
            lastModificationDate = pushResult.lastModificationDate,
            remoteMutations = finalRemoteMutations,
            localMutations = preprocessedLocalMutations
        )
    }

    // Pure business logic methods (no external dependencies)
    
    private fun preprocessLocalMutations(
        localMutations: List<LocalModelMutation<PageBookmark>>
    ): List<LocalModelMutation<PageBookmark>> {
        val preprocessor = LocalMutationsPreprocessor()
        return preprocessor.preprocess(localMutations)
    }
    
    private suspend fun preprocessRemoteMutations(
        remoteMutations: List<RemoteModelMutation<PageBookmark>>,
        checkLocalExistence: suspend (List<String>) -> Map<String, Boolean>
    ): List<RemoteModelMutation<PageBookmark>> {
        val preprocessor = RemoteMutationsPreprocessor(checkLocalExistence)
        return preprocessor.preprocess(remoteMutations)
    }
    


    private fun detectConflicts(
        remoteMutations: List<RemoteModelMutation<PageBookmark>>,
        localMutations: List<LocalModelMutation<PageBookmark>>
    ): ConflictDetectionResult<PageBookmark> {
        val conflictDetector = ConflictDetector(remoteMutations, localMutations)
        return conflictDetector.getConflicts()
    }

    private fun resolveConflicts(conflicts: List<ResourceConflict<PageBookmark>>): ConflictResolutionResult<PageBookmark> {
        val conflictResolver = ConflictResolver(conflicts)
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