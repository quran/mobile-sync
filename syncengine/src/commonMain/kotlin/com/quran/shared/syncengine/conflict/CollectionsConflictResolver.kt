package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncCollection

/**
 * Resolves conflicts between local and remote mutations for collections.
 *
 * The current strategy prefers remote mutations whenever a conflict is detected.
 */
class CollectionsConflictResolver(
    private val conflicts: List<ResourceConflict<SyncCollection>>
) {

    fun resolve(): ConflictResolutionResult<SyncCollection> {
        if (conflicts.isEmpty()) {
            return ConflictResolutionResult(listOf(), listOf())
        }

        val mutationsToPersist = conflicts.flatMap { it.remoteMutations }
        return ConflictResolutionResult(
            mutationsToPersist = mutationsToPersist,
            mutationsToPush = emptyList<LocalModelMutation<SyncCollection>>()
        )
    }
}
