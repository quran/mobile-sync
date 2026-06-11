package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation

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

internal fun <Model> buildConflictDetectionResult(
    resourceConflicts: List<ResourceConflict<Model>>,
    remoteMutations: List<RemoteModelMutation<Model>>,
    localMutations: List<LocalModelMutation<Model>>
): ConflictDetectionResult<Model> {
    val conflictingRemoteIds = resourceConflicts
        .flatMap { it.remoteMutations }
        .map { it.remoteID }
        .toSet()

    val conflictingLocalIds = resourceConflicts
        .flatMap { it.localMutations }
        .map { it.localID }
        .toSet()

    return ConflictDetectionResult(
        conflicts = resourceConflicts,
        nonConflictingRemoteMutations = remoteMutations.filterNot { conflictingRemoteIds.contains(it.remoteID) },
        nonConflictingLocalMutations = localMutations.filterNot { conflictingLocalIds.contains(it.localID) }
    )
}
