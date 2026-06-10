package com.quran.shared.syncengine.conflict

import com.quran.shared.syncengine.model.SyncNote

/**
 * Resolves conflicts between local and remote mutations for notes.
 *
 * Remote mutations normally win, except a local delete for a remote-backed note wins over a
 * replayed remote create echo for the same remote ID.
 */
class NotesConflictResolver(
    private val conflicts: List<ResourceConflict<SyncNote>>
) {

    fun resolve(): ConflictResolutionResult<SyncNote> {
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
