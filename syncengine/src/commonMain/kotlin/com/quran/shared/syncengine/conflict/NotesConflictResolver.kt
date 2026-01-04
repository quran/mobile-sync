package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.syncengine.model.SyncNote

/**
 * Resolves conflicts between local and remote mutations for notes.
 *
 * The current strategy prefers remote mutations whenever a conflict is detected.
 */
class NotesConflictResolver(
    private val conflicts: List<ResourceConflict<SyncNote>>
) {

    fun resolve(): ConflictResolutionResult<SyncNote> {
        if (conflicts.isEmpty()) {
            return ConflictResolutionResult(listOf(), listOf())
        }

        val mutationsToPersist = conflicts.flatMap { it.remoteMutations }
        return ConflictResolutionResult(
            mutationsToPersist = mutationsToPersist,
            mutationsToPush = emptyList<LocalModelMutation<SyncNote>>()
        )
    }
}
