package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation

data class ConflictResolutionResult<Model>(
    val mutationsToPersist: List<RemoteModelMutation<Model>>,
    val mutationsToPush: List<LocalModelMutation<Model>>
)

private fun <Model> ConflictResolutionResult<Model>.mergeWith(other: ConflictResolutionResult<Model>): ConflictResolutionResult<Model> {
    return ConflictResolutionResult(
        mutationsToPersist = this.mutationsToPersist + other.mutationsToPersist,
        mutationsToPush = this.mutationsToPush + other.mutationsToPush
    )
}

class ConflictResolver(val conflictGroups: List<ConflictGroup<PageBookmark>>) {

    fun resolve(): ConflictResolutionResult<PageBookmark> {
        if (conflictGroups.isNotEmpty()) {
            return conflictGroups.map { processConflict(it) }
                .reduce { one, two -> one.mergeWith(two) }
        }
        else {
            return ConflictResolutionResult(mutationsToPush = listOf(), mutationsToPersist = listOf())
        }
    }

    private fun processConflict(conflictGroup: ConflictGroup<PageBookmark>): ConflictResolutionResult<PageBookmark> {
        val remoteDeletion = conflictGroup.remoteMutations.firstOrNull { it.mutation == Mutation.DELETED }
        val localDeletion = conflictGroup.localMutations.firstOrNull { it.mutation == Mutation.DELETED }

        val remoteCreation = conflictGroup.remoteMutations.firstOrNull { it.mutation == Mutation.CREATED }
        val localCreation = conflictGroup.localMutations.firstOrNull { it.mutation == Mutation.CREATED }

        if (remoteDeletion != null && localDeletion != null) {
            // Both sides have deletions
            val mutationsToPersist = mutableListOf<RemoteModelMutation<PageBookmark>>()
            val mutationsToPush = mutableListOf<LocalModelMutation<PageBookmark>>()
            
            mutationsToPersist.add(remoteDeletion)

            if (remoteCreation != null) {
                // persist both remote mutations, ignore local mutations
                mutationsToPersist.add(remoteCreation)
            }
            else if (localCreation != null) {
                // persist remoteDeletion, push localCreation
                mutationsToPush.add(localCreation)
            }
            // else: only persist remoteDeletion, ignore the rest
            
            return ConflictResolutionResult(
                mutationsToPersist = mutationsToPersist,
                mutationsToPush = mutationsToPush
            )
        }
        else if (localDeletion != null) {
            // Only local deletion (no remote deletion)
            // Push all local mutations
            return ConflictResolutionResult(
                mutationsToPersist = listOf(),
                mutationsToPush = conflictGroup.localMutations
            )
        }
        else if (remoteCreation != null && localCreation != null) {
            // Both sides have creations (no deletions)
            // Persist remote creation, ignore local
            return ConflictResolutionResult(
                mutationsToPersist = listOf(remoteCreation),
                mutationsToPush = listOf()
            )
        }
        else {
            // This shouldn't happen if ConflictDetector is working correctly
            // Return empty result as fallback
            return ConflictResolutionResult(
                mutationsToPersist = listOf(),
                mutationsToPush = listOf()
            )
        }
    }
}