package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation

data class ConflictGroup<Model>(
    val localMutations: List<LocalModelMutation<Model>>,
    val conflictingRemoteMutations: List<RemoteModelMutation<Model>>
)

data class ConflictDetectionResult<Model>(
    val conflictGroups: List<ConflictGroup<Model>>,
    val nonConflictingRemoteMutations: List<RemoteModelMutation<Model>>,
    val nonConflictingLocalMutations: List<LocalModelMutation<Model>>
)

class ConflictDetector(
    val remoteModelMutations: List<RemoteModelMutation<PageBookmark>>,
    val localModelMutations: List<LocalModelMutation<PageBookmark>>
    ) {

    fun getConflicts(): ConflictDetectionResult<PageBookmark> {
        // TODO: We need to check if the list contains duplicates, and log that at least.
        val remoteMutationsByRemoteID = remoteModelMutations.associateBy { it.model.id }

        val remoteMutationsByPages = remoteModelMutations.groupBy { it.model.page }

        val conflictGroups: MutableList<ConflictGroup<PageBookmark>> = mutableListOf()
        val conflictingRemoteIDs: MutableSet<String> = mutableSetOf()
        val conflictingLocalIDs: MutableSet<String> = mutableSetOf()
        
        // Group local mutations by page
        val localMutationsByPage = localModelMutations.groupBy { it.model.page }
        
        localMutationsByPage.forEach { (page, localMutations) ->
            remoteMutationsByPages[page]?.let { remoteMutations ->
                // Handle multiple remote mutations for the same page
                conflictingRemoteIDs.addAll(remoteMutations.map { it.remoteID })
                conflictingLocalIDs.addAll(localMutations.map { it.localID })
                conflictGroups += ConflictGroup(
                    localMutations = localMutations,
                    conflictingRemoteMutations = remoteMutations
                )
            }
        }

        val remainingRemoteMutations = remoteModelMutations.filterNot { conflictingRemoteIDs.contains(it.remoteID) }
        val remainingLocalMutations = localModelMutations.filterNot { conflictingLocalIDs.contains(it.localID) }

        return ConflictDetectionResult(
            conflictGroups = conflictGroups,
            nonConflictingLocalMutations = remainingLocalMutations,
            nonConflictingRemoteMutations = remainingRemoteMutations
        )
    }
}