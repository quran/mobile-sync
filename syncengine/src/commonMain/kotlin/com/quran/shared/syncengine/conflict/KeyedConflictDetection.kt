package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation

internal fun <Model, Key : Any> detectKeyedConflicts(
    remoteMutations: List<RemoteModelMutation<Model>>,
    localMutations: List<LocalModelMutation<Model>>,
    remoteKey: (RemoteModelMutation<Model>) -> Key?,
    localKey: (LocalModelMutation<Model>) -> Key
): ConflictDetectionResult<Model> {
    val remoteMutationsByKey = remoteMutations
        .mapNotNull { mutation -> remoteKey(mutation)?.let { key -> key to mutation } }
        .groupBy({ it.first }, { it.second })
    val remoteMutationsByRemoteID = remoteMutations.associateBy { it.remoteID }

    val resourceConflicts = localMutations
        .groupBy(localKey)
        .mapNotNull { (key, localMutations) ->
            val remoteMutationsById = localMutations
                .mapNotNull { it.remoteID }
                .mapNotNull { remoteMutationsByRemoteID[it] }
            val conflictingRemoteMutations = (remoteMutationsByKey[key].orEmpty() + remoteMutationsById)
                .distinct()

            if (conflictingRemoteMutations.isEmpty()) {
                null
            } else {
                ResourceConflict(
                    localMutations = localMutations,
                    remoteMutations = conflictingRemoteMutations
                )
            }
        }

    return buildConflictDetectionResult(resourceConflicts, remoteMutations, localMutations)
}
