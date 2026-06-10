package com.quran.shared.syncengine.conflict

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

        val results = conflicts.map { conflict ->
            conflict.resolveLocalDeleteOverRemoteCreateEcho()
                ?: ConflictResolutionResult(
                    mutationsToPersist = conflict.remoteMutations,
                    mutationsToPush = emptyList()
                )
        }
        return ConflictResolutionResult(
            mutationsToPersist = results.flatMap { it.mutationsToPersist },
            mutationsToPush = results.flatMap { it.mutationsToPush }
        )
    }
}
