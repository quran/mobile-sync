package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation

/**
 * 
 * @param conflictGroups Groups of mutations that have conflicts
 * @param nonConflictingRemoteMutations Remote mutations that don't conflict with any local mutations
 * @param nonConflictingLocalMutations Local mutations that don't conflict with any remote mutations
 */
data class ConflictDetectionResult<Model>(
    val conflictGroups: List<ConflictGroup<Model>>,
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
        
        val conflictGroups = buildConflictGroups(remoteMutationsByPage, remoteMutationsByRemoteID)
        val conflictingIDs = extractConflictingIDs(conflictGroups)
        
        return buildResult(conflictGroups, conflictingIDs)
    }
    
    /**
     * Builds conflict groups by analyzing local mutations and finding corresponding remote conflicts.
     */
    private fun buildConflictGroups(
        remoteMutationsByPage: Map<Int, List<RemoteModelMutation<PageBookmark>>>,
        remoteMutationsByRemoteID: Map<String, RemoteModelMutation<PageBookmark>>
    ): List<ConflictGroup<PageBookmark>> {
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
                    ConflictGroup(
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
        val conflictingRemotes = mutableListOf<RemoteModelMutation<PageBookmark>>()
        
        // Add remote mutations for the same page
        remoteMutationsByPage[page]?.let { conflictingRemotes.addAll(it) }
        
        // Add remote mutations for deleted items (page = 0) that match local remote IDs
        localMutations.forEach { localMutation ->
            localMutation.remoteID?.let { remoteID ->
                val remoteMutation = remoteMutationsByRemoteID[remoteID]
                if (remoteMutation?.model?.page == PAGE_VAL_IN_NULLIFIED_MODEL) {
                    conflictingRemotes.add(remoteMutation)
                }
            }
        }
        
        return conflictingRemotes.distinctBy { it.remoteID }
    }

    private fun extractConflictingIDs(conflictGroups: List<ConflictGroup<PageBookmark>>): Pair<Set<String>, Set<String>> {
        val conflictingRemoteIDs = conflictGroups
            .flatMap { it.remoteMutations }
            .map { it.remoteID }
            .toSet()
            
        val conflictingLocalIDs = conflictGroups
            .flatMap { it.localMutations }
            .map { it.localID }
            .toSet()
            
        return Pair(conflictingRemoteIDs, conflictingLocalIDs)
    }
    
    private fun buildResult(
        conflictGroups: List<ConflictGroup<PageBookmark>>,
        conflictingIDs: Pair<Set<String>, Set<String>>
    ): ConflictDetectionResult<PageBookmark> {
        val (conflictingRemoteIDs, conflictingLocalIDs) = conflictingIDs
        
        val nonConflictingRemoteMutations = remoteMutations
            .filterNot { conflictingRemoteIDs.contains(it.remoteID) }
            
        val nonConflictingLocalMutations = localMutations
            .filterNot { conflictingLocalIDs.contains(it.localID) }
        
        return ConflictDetectionResult(
            conflictGroups = conflictGroups,
            nonConflictingRemoteMutations = nonConflictingRemoteMutations,
            nonConflictingLocalMutations = nonConflictingLocalMutations
        )
    }
}