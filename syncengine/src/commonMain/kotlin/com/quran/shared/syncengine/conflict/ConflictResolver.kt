package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.syncengine.model.SyncBookmark
import com.quran.shared.syncengine.model.conflictKey

/**
 * Resolves conflicts between local and remote mutations for bookmarks.
 * 
 * Analyzes conflict groups and determines which mutations should be persisted locally
 * and which should be pushed to the remote server.
 * 
 * Note: Illogical scenarios (e.g., local creation vs remote deletion) will raise
 * [IllegalArgumentException] as they indicate the two sides were not in sync.
 */
class ConflictResolver(private val conflicts: List<ResourceConflict<SyncBookmark>>) {

    /**
     * Resolves all conflicts and returns the mutations to persist and push.
     * 
     * @return [ConflictResolutionResult] containing mutations to persist locally and push remotely
     * @throws IllegalArgumentException when illogical conflict scenarios are detected
     */
    fun resolve(): ConflictResolutionResult<SyncBookmark> {
        return conflicts
            .map { processConflict(it) }
            .mergeConflictResolutionResults()
    }

    private fun processConflict(resourceConflict: ResourceConflict<SyncBookmark>): ConflictResolutionResult<SyncBookmark> {
        // Illogical scenarios
        if (resourceConflict.hasOnly(local = listOf(Mutation.CREATED), remote = listOf(Mutation.DELETED))) {
            throw resourceConflict.illogicalConflict("Local creation conflicts with remote deletion")
        }

        resourceConflict.resolveLocalDeleteOverRemoteCreateEcho()?.let { return it }

        if (resourceConflict.hasOnly(local = listOf(Mutation.DELETED), remote = listOf(Mutation.CREATED))) {
            throw resourceConflict.illogicalConflict("Local deletion conflicts with remote creation")
        }
        
        // Handling conflicts
        resolveReadingCreateConflict(resourceConflict)?.let { return it }

        if (resourceConflict.remoteWinsByPolicy()) {
            return persistRemoteMutations(resourceConflict.remoteMutations)
        }
        else if (resourceConflict.localCreateAfterSharedDeleteWinsByPolicy()) {
            return ConflictResolutionResult(
                mutationsToPush = resourceConflict.localMutations.filter { it.mutation == Mutation.CREATED },
                mutationsToPersist = resourceConflict.remoteMutations
            )
        }
        else {
            // This shouldn't happen if ConflictDetector is working correctly
            // Throw an error instead of returning empty result as fallback
            throw resourceConflict.unexpectedConflict()
        }
    }

    private fun resolveReadingCreateConflict(
        resourceConflict: ResourceConflict<SyncBookmark>
    ): ConflictResolutionResult<SyncBookmark>? {
        val localMutation = resourceConflict.localMutations.singleOrNull()
        val remoteMutation = resourceConflict.remoteMutations.singleOrNull()
        if (localMutation?.mutation != Mutation.CREATED || remoteMutation?.mutation != Mutation.CREATED) {
            return null
        }
        if (localMutation.model.isReading == remoteMutation.model.isReading) {
            return null
        }
        if (localMutation.model.conflictKey() != remoteMutation.model.conflictKey()) {
            return if (localMutation.model.lastModified > remoteMutation.model.lastModified) {
                ConflictResolutionResult(
                    mutationsToPush = listOf(
                        localMutation.copySyncState(remoteID = null, mutation = Mutation.CREATED)
                    ),
                    mutationsToPersist = listOf(remoteMutation)
                )
            } else {
                null
            }
        }

        return if (localMutation.model.lastModified > remoteMutation.model.lastModified) {
            val remoteIdToUpdate = localMutation.remoteID ?: remoteMutation.remoteID
            pushLocalMutation(localMutation.copySyncState(remoteID = remoteIdToUpdate, mutation = Mutation.MODIFIED))
        } else {
            persistRemoteMutation(remoteMutation)
        }
    }
}

private fun LocalModelMutation<SyncBookmark>.copySyncState(
    remoteID: String?,
    mutation: Mutation
): LocalModelMutation<SyncBookmark> =
    LocalModelMutation(
        model = model,
        remoteID = remoteID,
        localID = localID,
        mutation = mutation,
        ack = ack
    )

private fun ResourceConflict<SyncBookmark>.remoteWinsByPolicy(): Boolean {
    return hasOnly(local = listOf(Mutation.CREATED), remote = listOf(Mutation.CREATED)) ||
        hasOnly(local = listOf(Mutation.DELETED), remote = listOf(Mutation.DELETED)) ||
        hasOnly(local = listOf(Mutation.DELETED), remote = listOf(Mutation.DELETED, Mutation.CREATED)) ||
        hasOnly(local = listOf(Mutation.CREATED, Mutation.DELETED), remote = listOf(Mutation.CREATED, Mutation.DELETED))
}

private fun ResourceConflict<SyncBookmark>.localCreateAfterSharedDeleteWinsByPolicy(): Boolean {
    return hasOnly(local = listOf(Mutation.DELETED, Mutation.CREATED), remote = listOf(Mutation.DELETED))
}

private fun ResourceConflict<SyncBookmark>.illogicalConflict(reason: String): IllegalArgumentException {
    return IllegalArgumentException(
        "Illogical scenario detected: $reason. " +
            "This indicates the two sides were not in sync. " +
            mutationDetails()
    )
}

private fun ResourceConflict<SyncBookmark>.unexpectedConflict(): IllegalArgumentException {
    return IllegalArgumentException(
        "Unexpected conflict scenario detected. " +
            mutationDetails()
    )
}

private fun ResourceConflict<SyncBookmark>.mutationDetails(): String {
    return "Local mutations: ${localMutations.map { "${it.mutation}(${it.localID})" }}, " +
        "Remote mutations: ${remoteMutations.map { "${it.mutation}(${it.remoteID})" }}"
}

private fun ResourceConflict<*>.hasOnly(
    local: List<Mutation> = emptyList(),
    remote: List<Mutation> = emptyList()
): Boolean {
    return localMutations.map { it.mutation }.hasSameMutationCountsAs(local) &&
        remoteMutations.map { it.mutation }.hasSameMutationCountsAs(remote)
}

private fun List<Mutation>.hasSameMutationCountsAs(expected: List<Mutation>): Boolean {
    return size == expected.size &&
        groupingBy { it }.eachCount() == expected.groupingBy { it }.eachCount()
}
