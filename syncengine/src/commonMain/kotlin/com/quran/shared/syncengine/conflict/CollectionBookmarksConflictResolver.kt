package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.syncengine.model.SyncCollectionBookmark

/**
 * Resolves conflicts between local and remote mutations for collection bookmarks.
 *
 * The current strategy prefers remote mutations whenever a conflict is detected.
 */
class CollectionBookmarksConflictResolver(
    private val conflicts: List<ResourceConflict<SyncCollectionBookmark>>
) {

    fun resolve(): ConflictResolutionResult<SyncCollectionBookmark> {
        if (conflicts.isEmpty()) {
            return ConflictResolutionResult(listOf(), listOf())
        }

        val mutationsToPersist = conflicts.flatMap { it.remoteMutations }
        return ConflictResolutionResult(
            mutationsToPersist = mutationsToPersist,
            mutationsToPush = emptyList<LocalModelMutation<SyncCollectionBookmark>>()
        )
    }
}
