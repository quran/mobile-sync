package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation

data class ConflictDetectionResult<Model>(
    val conflicts: List<ModelConflict<Model>>,
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

        val conflicts: MutableList<ModelConflict<PageBookmark>> = mutableListOf()
        val conflictingRemoteIDs: MutableSet<String> = mutableSetOf()
        val conflictingLocalIDs: MutableSet<String> = mutableSetOf()
        localModelMutations.forEach { localMutation ->
            remoteMutationsByRemoteID[localMutation.remoteID]?.let {

            }

            remoteMutationsByPages[localMutation.model.page]?.let {
                // TODO: Deal with lists.
                val remote = it.last()!!
                conflictingRemoteIDs.add(remote.remoteID)
                conflictingLocalIDs.add(localMutation.localID)
                conflicts += ModelConflict(
                    remoteModelMutation = remote,
                    localModelMutation = localMutation
                )
            }
        }

        val remainingRemoteMutations = remoteModelMutations.filterNot { conflictingRemoteIDs.contains(it.remoteID) }
        val remainingLocalMutations = localModelMutations.filterNot { conflictingLocalIDs.contains(it.localID) }

        return ConflictDetectionResult(
            conflicts = conflicts,
            nonConflictingLocalMutations = remainingLocalMutations,
            nonConflictingRemoteMutations = remainingRemoteMutations
        )
    }
}