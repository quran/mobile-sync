package com.quran.shared.syncengine

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.conflict.ConflictDetectionResult
import com.quran.shared.syncengine.conflict.ConflictDetector
import com.quran.shared.syncengine.conflict.ConflictResolutionResult
import com.quran.shared.syncengine.conflict.ConflictResolver
import com.quran.shared.syncengine.conflict.ResourceConflict
import com.quran.shared.syncengine.model.SyncBookmark
import com.quran.shared.syncengine.preprocessing.LocalMutationsPreprocessor
import com.quran.shared.syncengine.preprocessing.RemoteMutationsPreprocessor

/**
 * Pure business logic executor for bookmark synchronization operations.
 * Contains no external dependencies and is fully testable.
 */
class BookmarksSynchronizationExecutor {
    
    private val logger = Logger.withTag("SynchronizationExecutor")
    
    // Pipeline Step Data Classes
    data class PipelineInitData(
        val lastModificationDate: Long,
        val localMutations: List<LocalModelMutation<SyncBookmark>>
    )

    data class FetchedRemoteData(
        val remoteMutations: List<RemoteModelMutation<SyncBookmark>>,
        val lastModificationDate: Long
    )

    data class PushResultData(
        val pushedMutations: List<RemoteModelMutation<SyncBookmark>>,
        val lastModificationDate: Long
    )

    data class PipelineResult(
        val lastModificationDate: Long,
        val remoteMutations: List<RemoteModelMutation<SyncBookmark>>,
        val localMutations: List<LocalModelMutation<SyncBookmark>>
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
        pushLocal: suspend (List<LocalModelMutation<SyncBookmark>>, Long) -> PushResultData
    ): PipelineResult {
        logger.i { "Starting synchronization execution for bookmarks." }
        
        val pipelineData = fetchLocal()
        logger.i { "Initialized with lastModificationDate=${pipelineData.lastModificationDate}, localMutations=${pipelineData.localMutations.size}" }
        
        val preprocessedLocalMutations = preprocessLocalMutations(pipelineData.localMutations)
        logger.d { "Local mutations preprocessed: ${pipelineData.localMutations.size} -> ${preprocessedLocalMutations.size}" }
        
        val fetchedData = fetchRemote(pipelineData.lastModificationDate)
        logger.d { "Remote mutations fetched: ${fetchedData.remoteMutations.size}, new lastModificationDate=${fetchedData.lastModificationDate}" }
        
        val preprocessedRemoteMutations = preprocessRemoteMutations(fetchedData.remoteMutations, checkLocalExistence)
        logger.d { "Remote mutations preprocessed: ${fetchedData.remoteMutations.size} -> ${preprocessedRemoteMutations.size}" }
        
        val conflictDetectionResult = detectConflicts(preprocessedRemoteMutations, preprocessedLocalMutations)
        logger.d { "Conflict detection completed: ${conflictDetectionResult.conflicts.size} conflicts found, ${conflictDetectionResult.nonConflictingLocalMutations.size} non-conflicting local mutations, ${conflictDetectionResult.nonConflictingRemoteMutations.size} non-conflicting remote mutations" }
        
        val conflictResolutionResult = resolveConflicts(conflictDetectionResult.conflicts)
        logger.d { "Conflict resolution completed: ${conflictResolutionResult.mutationsToPersist.size} mutations to persist, ${conflictResolutionResult.mutationsToPush.size} mutations to push" }
        
        val mutationsToPush = conflictDetectionResult.nonConflictingLocalMutations + conflictResolutionResult.mutationsToPush
        logger.i { "Pushing ${mutationsToPush.size} local mutations to server" }

        val pushResult = pushLocal(mutationsToPush, fetchedData.lastModificationDate)
        logger.d { "Push completed: received ${pushResult.pushedMutations.size} pushed remote mutations, new lastModificationDate=${pushResult.lastModificationDate}" }
        
        val preprocessedPushedMutations = preprocessRemoteMutations(pushResult.pushedMutations, checkLocalExistence)
        logger.d { "Pushed mutations preprocessed: ${pushResult.pushedMutations.size} -> ${preprocessedPushedMutations.size}" }
        
        val finalRemoteMutations = combineRemoteMutations(
            conflictDetectionResult.nonConflictingRemoteMutations,
            conflictResolutionResult.mutationsToPersist,
            preprocessedPushedMutations
        )
        
        logger.i { "Synchronization completed successfully: ${finalRemoteMutations.size} remote mutations to persist, ${preprocessedLocalMutations.size} local mutations to clear" }
        return PipelineResult(
            lastModificationDate = pushResult.lastModificationDate,
            remoteMutations = finalRemoteMutations,
            localMutations = preprocessedLocalMutations
        )
    }

    private fun preprocessLocalMutations(
        localMutations: List<LocalModelMutation<SyncBookmark>>
    ): List<LocalModelMutation<SyncBookmark>> {
        val preprocessor = LocalMutationsPreprocessor()
        return preprocessor.preprocess(localMutations)
    }
    
    private suspend fun preprocessRemoteMutations(
        remoteMutations: List<RemoteModelMutation<SyncBookmark>>,
        checkLocalExistence: suspend (List<String>) -> Map<String, Boolean>
    ): List<RemoteModelMutation<SyncBookmark>> {
        val preprocessor = RemoteMutationsPreprocessor(checkLocalExistence)
        return preprocessor.preprocess(remoteMutations)
    }
    


    private fun detectConflicts(
        remoteMutations: List<RemoteModelMutation<SyncBookmark>>,
        localMutations: List<LocalModelMutation<SyncBookmark>>
    ): ConflictDetectionResult<SyncBookmark> {
        val conflictDetector = ConflictDetector(remoteMutations, localMutations)
        return conflictDetector.getConflicts()
    }

    private fun resolveConflicts(conflicts: List<ResourceConflict<SyncBookmark>>): ConflictResolutionResult<SyncBookmark> {
        val conflictResolver = ConflictResolver(conflicts)
        return conflictResolver.resolve()
    }

    private fun combineRemoteMutations(
        otherRemoteMutations: List<RemoteModelMutation<SyncBookmark>>,
        mutationsToPersist: List<RemoteModelMutation<SyncBookmark>>,
        pushedMutations: List<RemoteModelMutation<SyncBookmark>>
    ): List<RemoteModelMutation<SyncBookmark>> {
        return otherRemoteMutations + mutationsToPersist + pushedMutations
    }
} 
