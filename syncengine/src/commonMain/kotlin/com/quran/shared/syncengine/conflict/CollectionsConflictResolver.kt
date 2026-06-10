package com.quran.shared.syncengine.conflict

import com.quran.shared.syncengine.model.SyncCollection

/**
 * Resolves conflicts between local and remote mutations for collections.
 *
 * Remote mutations normally win, except a local delete for a remote-backed collection wins over
 * a replayed remote create echo for the same remote ID.
 */
class CollectionsConflictResolver(
    private val conflicts: List<ResourceConflict<SyncCollection>>
) {

    fun resolve(): ConflictResolutionResult<SyncCollection> {
        if (conflicts.isEmpty()) {
            return ConflictResolutionResult(listOf(), listOf())
        }

        val resolvedConflicts = conflicts.map { conflict ->
            conflict.resolveLocalDeleteOverRemoteCreateEcho() ?: ConflictResolutionResult(
                mutationsToPersist = conflict.remoteMutations,
                mutationsToPush = emptyList()
            )
        }
        return ConflictResolutionResult(
            mutationsToPersist = resolvedConflicts.flatMap { it.mutationsToPersist },
            mutationsToPush = resolvedConflicts.flatMap { it.mutationsToPush }
        )
    }
}
