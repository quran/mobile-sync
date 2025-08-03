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

class ConflictResolver(private val conflictGroups: List<ConflictGroup<PageBookmark>>) {

    fun resolve(): ConflictResolutionResult<PageBookmark> {
        if (conflictGroups.isNotEmpty()) {
            return conflictGroups.map { processConflict(it) }
                .reduce { one, other -> one.mergeWith(other) }
        }
        else {
            return ConflictResolutionResult(listOf(), listOf())
        }
    }

    private fun processConflict(conflictGroup: ConflictGroup<PageBookmark>): ConflictResolutionResult<PageBookmark> {
        if (conflictGroup.mustHave(Mutation.CREATED, MutationSide.BOTH).only() ||
            conflictGroup.mustHave(Mutation.DELETED, MutationSide.BOTH).only() ||
            conflictGroup.mustHave(Mutation.DELETED, MutationSide.BOTH)
                .and(Mutation.CREATED, MutationSide.REMOTE)
                .only() ||
            conflictGroup.mustHave(Mutation.CREATED, MutationSide.BOTH)
                .and(Mutation.DELETED, MutationSide.BOTH)
                .only()) {
            return ConflictResolutionResult(
                mutationsToPush = listOf(),
                mutationsToPersist = conflictGroup.remoteMutations
            )
        }
        else if (conflictGroup.mustHave(Mutation.DELETED, MutationSide.BOTH)
            .and(Mutation.CREATED, MutationSide.LOCAL)
            .only()) {
            return ConflictResolutionResult(
                mutationsToPush = conflictGroup.localMutations.filter { it.mutation == Mutation.CREATED },
                mutationsToPersist = conflictGroup.remoteMutations
            )
        }
        else {
            // TODO: Consider an error for this.
            // This shouldn't happen if ConflictDetector is working correctly
            // Return empty result as fallback
            return ConflictResolutionResult(listOf(), listOf())
        }
    }
}

private enum class MutationSide {
    REMOTE, LOCAL, BOTH
}

private fun <Model> ConflictGroup<Model>.mustHave(
    mutation: Mutation,
    side: MutationSide
): ConflictPredicate<Model> {
    if (side == MutationSide.REMOTE) {
        val instance = remoteMutations.firstOrNull { it.mutation == mutation }
        if (instance == null) {
            return ConflictPredicate(hasFailed = true, conflictGroup = this)
        }
        else {
            val newGroup = ConflictGroup(
                remoteMutations = remoteMutations.minus(instance),
                localMutations = localMutations
            )
            return ConflictPredicate(hasFailed = false, conflictGroup = newGroup)
        }
    }
    else if (side == MutationSide.LOCAL){
        val instance = localMutations.firstOrNull { it.mutation == mutation }
        if (instance == null) {
            return ConflictPredicate(hasFailed = true, conflictGroup = this)
        }
        else {
            val newGroup = ConflictGroup(
                remoteMutations = remoteMutations,
                localMutations = localMutations.minus(instance)
            )
            return ConflictPredicate(hasFailed = false, conflictGroup = newGroup)
        }
    }
    else {
        return mustHave(mutation, MutationSide.REMOTE).and(mutation, MutationSide.LOCAL)
    }
}

private class ConflictPredicate<Model>(
    private val hasFailed: Boolean,
    private val conflictGroup: ConflictGroup<Model>
    ) {

    fun and(mutation: Mutation, side: MutationSide): ConflictPredicate<Model> {
        if (hasFailed) {
            return this
        }
        return conflictGroup.mustHave(mutation, side)
    }

    fun only(): Boolean =!hasFailed
            && conflictGroup.remoteMutations.isEmpty()
            && conflictGroup.localMutations.isEmpty()
}