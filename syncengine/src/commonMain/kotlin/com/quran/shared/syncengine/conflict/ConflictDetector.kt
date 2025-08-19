package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.PageBookmark

/**
 * 
 * @param conflicts Groups of mutations that have conflicts
 * @param nonConflictingRemoteMutations Remote mutations that don't conflict with any local mutations
 * @param nonConflictingLocalMutations Local mutations that don't conflict with any remote mutations
 */
data class ConflictDetectionResult<Model>(
    val conflicts: List<ResourceConflict<Model>>,
    val nonConflictingRemoteMutations: List<RemoteModelMutation<Model>>,
    val nonConflictingLocalMutations: List<LocalModelMutation<Model>>
)

/**
 * Detects conflicts between local and remote mutations for page bookmarks.
 * 
 * A conflict is detected whenever a set of local and remote mutations reference the same bookmark,
 * let it be a single resource with the same ID, or different resources for the same page.
 * 
 * The detector groups conflicts by page and provides separate lists for non-conflicting mutations.
 */
class ConflictDetector(
    private val remoteMutations: List<RemoteModelMutation<PageBookmark>>,
    private val localMutations: List<LocalModelMutation<PageBookmark>>
) {
    
    companion object {
        // Remote DELETE events come without associated data for the resource before deletion.
        // TODO: Planned to properly express empty resources in remote DELETE events.
        private const val PAGE_VAL_IN_NULLIFIED_MODEL = 0
    }

    fun getConflicts(): ConflictDetectionResult<PageBookmark> {
        val remoteMutationsByPage = remoteMutations.groupBy { it.model.page }
        val remoteMutationsByRemoteID = remoteMutations.associateBy { it.remoteID }
        
        val resourceConflicts = buildResourceConflicts(remoteMutationsByPage, remoteMutationsByRemoteID)
        val conflictingIDs = extractConflictingIDs(resourceConflicts)
        
        return buildResult(resourceConflicts, conflictingIDs)
    }
    
    /**
     * Builds resource conflicts by analyzing local mutations and finding corresponding remote conflicts.
     */
    private fun buildResourceConflicts(
        remoteMutationsByPage: Map<Int, List<RemoteModelMutation<PageBookmark>>>,
        remoteMutationsByRemoteID: Map<String, RemoteModelMutation<PageBookmark>>
    ): List<ResourceConflict<PageBookmark>> {
        return localMutations
            .groupBy { it.model.page }
            .mapNotNull { (page, localMutations) ->
                val conflictingRemoteMutations = findConflictingRemoteMutations(
                    page,
                    localMutations, 
                    remoteMutationsByPage, 
                    remoteMutationsByRemoteID
                )
                
                if (conflictingRemoteMutations.isNotEmpty()) {
                    ResourceConflict(
                        localMutations = localMutations,
                        remoteMutations = conflictingRemoteMutations
                    )
                } else null
            }
    }

    private fun findConflictingRemoteMutations(
        page: Int,
        localMutations: List<LocalModelMutation<PageBookmark>>,
        remoteMutationsByPage: Map<Int, List<RemoteModelMutation<PageBookmark>>>,
        remoteMutationsByRemoteID: Map<String, RemoteModelMutation<PageBookmark>>
    ): List<RemoteModelMutation<PageBookmark>> {
        return remoteMutationsByPage[page].orEmpty() +
                localMutations.mapNotNull { it.remoteID }
                    .mapNotNull { remoteMutationsByRemoteID[it] }
                    .filter { it.model.page == PAGE_VAL_IN_NULLIFIED_MODEL }
    }

    private fun extractConflictingIDs(resourceConflicts: List<ResourceConflict<PageBookmark>>): Pair<Set<String>, Set<String>> {
        val conflictingRemoteIDs = resourceConflicts
            .flatMap { it.remoteMutations }
            .map { it.remoteID }
            .toSet()
            
        val conflictingLocalIDs = resourceConflicts
            .flatMap { it.localMutations }
            .map { it.localID }
            .toSet()
            
        return Pair(conflictingRemoteIDs, conflictingLocalIDs)
    }
    
    private fun buildResult(
        resourceConflicts: List<ResourceConflict<PageBookmark>>,
        conflictingIDs: Pair<Set<String>, Set<String>>
    ): ConflictDetectionResult<PageBookmark> {
        val (conflictingRemoteIDs, conflictingLocalIDs) = conflictingIDs
        
        val nonConflictingRemoteMutations = remoteMutations
            .filterNot { conflictingRemoteIDs.contains(it.remoteID) }
            
        val nonConflictingLocalMutations = localMutations
            .filterNot { conflictingLocalIDs.contains(it.localID) }
        
        return ConflictDetectionResult(
            conflicts = resourceConflicts,
            nonConflictingRemoteMutations = nonConflictingRemoteMutations,
            nonConflictingLocalMutations = nonConflictingLocalMutations
        )
    }
}