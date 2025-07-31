package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation

/**
 * Represents a group of conflicting local and remote mutations for the same resource.
 */
data class ConflictGroup<Model>(
    val localMutations: List<LocalModelMutation<Model>>,
    val remoteMutations: List<RemoteModelMutation<Model>>
)

/**
 * Result of conflict detection containing all conflict groups and non-conflicting mutations.
 * 
 * @param conflictGroups Groups of mutations that have conflicts
 * @param otherRemoteMutations Remote mutations that don't conflict with any local mutations
 * @param otherLocalMutations Local mutations that don't conflict with any remote mutations
 */
data class ConflictDetectionResult<Model>(
    val conflictGroups: List<ConflictGroup<Model>>,
    val otherRemoteMutations: List<RemoteModelMutation<Model>>,
    val otherLocalMutations: List<LocalModelMutation<Model>>
)

/**
 * Detects conflicts between local and remote mutations for page bookmarks.
 * 
 * A conflict occurs when:
 * 1. Local and remote mutations target the same page
 * 2. Local mutations reference a remote ID that exists in remote mutations (for deleted items)
 * 
 * The detector groups conflicts by page and provides separate lists for non-conflicting mutations.
 */
class ConflictDetector(
    private val remoteModelMutations: List<RemoteModelMutation<PageBookmark>>,
    private val localModelMutations: List<LocalModelMutation<PageBookmark>>
) {
    
    companion object {
        private const val PAGE_VAL_IN_NULLIFIED_MODEL = 0
    }

    fun getConflicts(): ConflictDetectionResult<PageBookmark> {
        val remoteMutationsByPage = remoteModelMutations.groupBy { it.model.page }
        val remoteMutationsByRemoteID = remoteModelMutations.associateBy { it.remoteID }
        
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
        return localModelMutations
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
    
    /**
     * Finds remote mutations that conflict with the given local mutations.
     * 
     * Conflicts are detected when:
     * 1. Remote mutations target the same page as local mutations
     * 2. Local mutations reference a remote ID that exists in remote mutations (for deleted items)
     */
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

    /**
     * Extracts all conflicting remote and local IDs from conflict groups.
     */
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
        
        val nonConflictingRemoteMutations = remoteModelMutations
            .filterNot { conflictingRemoteIDs.contains(it.remoteID) }
            
        val nonConflictingLocalMutations = localModelMutations
            .filterNot { conflictingLocalIDs.contains(it.localID) }
        
        return ConflictDetectionResult(
            conflictGroups = conflictGroups,
            otherRemoteMutations = nonConflictingRemoteMutations,
            otherLocalMutations = nonConflictingLocalMutations
        )
    }
}