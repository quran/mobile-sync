package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation

internal fun <Model> detectRemoteIdConflicts(
    remoteMutations: List<RemoteModelMutation<Model>>,
    localMutations: List<LocalModelMutation<Model>>,
    matchesSemanticReplay: (LocalModelMutation<Model>, RemoteModelMutation<Model>) -> Boolean
): ConflictDetectionResult<Model> {
    val remoteMutationsByRemoteId = remoteMutations.groupBy { it.remoteID }
    val localMutationsByRemoteId = localMutations
        .mapNotNull { local ->
            local.remoteID?.let { remoteId -> remoteId to local }
        }
        .groupBy({ it.first }, { it.second })

    val resourceConflicts = localMutationsByRemoteId.mapNotNull { (remoteId, locals) ->
        val remotes = remoteMutationsByRemoteId[remoteId].orEmpty()
        if (remotes.isEmpty()) {
            null
        } else {
            ResourceConflict(
                localMutations = locals,
                remoteMutations = remotes
            )
        }
    }.toMutableList()

    val conflictingRemoteIds = resourceConflicts
        .flatMap { it.remoteMutations }
        .map { it.remoteID }
        .toMutableSet()

    val conflictingLocalIds = resourceConflicts
        .flatMap { it.localMutations }
        .map { it.localID }
        .toMutableSet()

    remoteMutations
        .filter { it.mutation == Mutation.CREATED && it.remoteID !in conflictingRemoteIds }
        .forEach { remote ->
            val matchingLocals = localMutations.filter { local ->
                local.localID !in conflictingLocalIds &&
                    local.remoteID == null &&
                    local.mutation == Mutation.CREATED &&
                    matchesSemanticReplay(local, remote)
            }
            if (matchingLocals.size == 1) {
                resourceConflicts += ResourceConflict(
                    localMutations = matchingLocals,
                    remoteMutations = listOf(remote)
                )
                conflictingLocalIds += matchingLocals.single().localID
                conflictingRemoteIds += remote.remoteID
            }
        }

    return buildConflictDetectionResult(resourceConflicts, remoteMutations, localMutations)
}
