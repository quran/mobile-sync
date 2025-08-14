package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation

// region: Result
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
// endregion:

/**
 * Resolves conflicts between local and remote mutations for page bookmarks.
 * 
 * Analyzes conflict groups and determines which mutations should be persisted locally
 * and which should be pushed to the remote server.
 * 
 * Note: Illogical scenarios (e.g., local creation vs remote deletion) will raise
 * [IllegalArgumentException] as they indicate the two sides were not in sync.
 */
class ConflictResolver(private val conflictGroups: List<ConflictGroup<PageBookmark>>) {

    /**
     * Resolves all conflicts and returns the mutations to persist and push.
     * 
     * @return [ConflictResolutionResult] containing mutations to persist locally and push remotely
     * @throws IllegalArgumentException when illogical conflict scenarios are detected
     */
    fun resolve(): ConflictResolutionResult<PageBookmark> {
        return if (conflictGroups.isNotEmpty()) {
            conflictGroups.map { processConflict(it) }
                .reduce { one, other -> one.mergeWith(other) }
        } else {
            ConflictResolutionResult(listOf(), listOf())
        }
    }

    private fun processConflict(conflictGroup: ConflictGroup<PageBookmark>): ConflictResolutionResult<PageBookmark> {
        // Check for illogical scenario: local creation vs remote deletion
        if (conflictGroup.mustHave(Mutation.CREATED, MutationSide.LOCAL)
            .and(Mutation.DELETED, MutationSide.REMOTE)
            .only()) {
            throw IllegalArgumentException(
                "Illogical scenario detected: Local creation conflicts with remote deletion. " +
                "This indicates the two sides were not in sync. " +
                "Local mutations: ${conflictGroup.localMutations.map { "${it.mutation}(${it.localID})" }}, " +
                "Remote mutations: ${conflictGroup.remoteMutations.map { "${it.mutation}(${it.remoteID})" }}"
            )
        }
        
        // Check for illogical scenario: local deletion vs remote creation
        if (conflictGroup.mustHave(Mutation.DELETED, MutationSide.LOCAL)
            .and(Mutation.CREATED, MutationSide.REMOTE)
            .only()) {
            throw IllegalArgumentException(
                "Illogical scenario detected: Local deletion conflicts with remote creation. " +
                "This indicates the two sides were not in sync. " +
                "Local mutations: ${conflictGroup.localMutations.map { "${it.mutation}(${it.localID})" }}, " +
                "Remote mutations: ${conflictGroup.remoteMutations.map { "${it.mutation}(${it.remoteID})" }}"
            )
        }
        
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

// region: ConflictPredicate

/**
 * A DSL that makes checking for specific conflicts clearer.
 */
private class ConflictPredicate<Model>(
    private val hasFailed: Boolean,
    private val remainingConflictGroup: ConflictGroup<Model>
) {

    fun and(mutation: Mutation, side: MutationSide): ConflictPredicate<Model> {
        if (hasFailed) {
            return this
        }
        return remainingConflictGroup.mustHave(mutation, side)
    }

    fun only(): Boolean = !hasFailed
            && remainingConflictGroup.remoteMutations.isEmpty()
            && remainingConflictGroup.localMutations.isEmpty()
}

private enum class MutationSide {
    REMOTE, LOCAL, BOTH
}

/**
 * Checks if this conflict group contains a specific mutation on the specified side.
 *
 * @return A predicate that can be chained with additional checks
 */
private fun <Model> ConflictGroup<Model>.mustHave(
    mutation: Mutation,
    side: MutationSide
): ConflictPredicate<Model> {
    return when (side) {
        MutationSide.REMOTE -> checkRemoteSide(mutation)
        MutationSide.LOCAL -> checkLocalSide(mutation)
        MutationSide.BOTH -> checkBothSides(mutation)
    }
}

private fun <Model> ConflictGroup<Model>.checkRemoteSide(mutation: Mutation): ConflictPredicate<Model> {
    val matchingRemoteMutation = remoteMutations.firstOrNull { it.mutation == mutation }
    return if (matchingRemoteMutation == null) {
        ConflictPredicate(hasFailed = true, remainingConflictGroup = this)
    } else {
        val remainingGroup = ConflictGroup(
            remoteMutations = remoteMutations.minus(matchingRemoteMutation),
            localMutations = localMutations
        )
        ConflictPredicate(hasFailed = false, remainingConflictGroup = remainingGroup)
    }
}

private fun <Model> ConflictGroup<Model>.checkLocalSide(mutation: Mutation): ConflictPredicate<Model> {
    val matchingLocalMutation = localMutations.firstOrNull { it.mutation == mutation }
    return if (matchingLocalMutation == null) {
        ConflictPredicate(hasFailed = true, remainingConflictGroup = this)
    } else {
        val remainingGroup = ConflictGroup(
            remoteMutations = remoteMutations,
            localMutations = localMutations.minus(matchingLocalMutation)
        )
        ConflictPredicate(hasFailed = false, remainingConflictGroup = remainingGroup)
    }
}

private fun <Model> ConflictGroup<Model>.checkBothSides(mutation: Mutation): ConflictPredicate<Model> {
    return checkRemoteSide(mutation).and(mutation, MutationSide.LOCAL)
}

// endregion: