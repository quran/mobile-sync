package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation

data class ConflictResolutionResult<Model>(
    val mutationsToPersist: List<RemoteModelMutation<Model>>,
    val mutationsToPush: List<LocalModelMutation<Model>>
)

internal fun <Model> persistRemoteMutation(
    mutation: RemoteModelMutation<Model>
): ConflictResolutionResult<Model> = persistRemoteMutations(listOf(mutation))

internal fun <Model> persistRemoteMutations(
    mutations: List<RemoteModelMutation<Model>>
): ConflictResolutionResult<Model> {
    return ConflictResolutionResult(
        mutationsToPersist = mutations,
        mutationsToPush = emptyList()
    )
}

internal fun <Model> pushLocalMutation(
    mutation: LocalModelMutation<Model>
): ConflictResolutionResult<Model> {
    return ConflictResolutionResult(
        mutationsToPersist = emptyList(),
        mutationsToPush = listOf(mutation)
    )
}

internal fun <Model> Iterable<ConflictResolutionResult<Model>>.mergeConflictResolutionResults(): ConflictResolutionResult<Model> {
    return ConflictResolutionResult(
        mutationsToPersist = flatMap { it.mutationsToPersist },
        mutationsToPush = flatMap { it.mutationsToPush }
    )
}
