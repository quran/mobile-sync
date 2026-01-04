package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncCollectionBookmark
import com.quran.shared.syncengine.model.SyncCollectionBookmarkKey
import com.quran.shared.syncengine.model.conflictKey

/**
 * Detects conflicts between local and remote mutations for collection bookmarks.
 *
 * A conflict is detected whenever a set of local and remote mutations reference the same
 * collection-bookmark key, or when a remote mutation matches a local remote ID.
 */
class CollectionBookmarksConflictDetector(
    private val remoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
    private val localMutations: List<LocalModelMutation<SyncCollectionBookmark>>
) {

    fun getConflicts(): ConflictDetectionResult<SyncCollectionBookmark> {
        val remoteMutationsByKey = remoteMutations
            .map { mutation -> mutation.model.conflictKey().let { key -> key to mutation } }
            .groupBy({ it.first }, { it.second })
        val remoteMutationsByRemoteID = remoteMutations.associateBy { it.remoteID }

        val resourceConflicts = localMutations
            .groupBy { mutation -> mutation.model.conflictKey() }
            .mapNotNull { (bookmarkKey, localMutations) ->
                val conflictingRemoteMutations = findConflictingRemoteMutations(
                    bookmarkKey,
                    localMutations,
                    remoteMutationsByKey,
                    remoteMutationsByRemoteID
                )
                if (conflictingRemoteMutations.isNotEmpty()) {
                    ResourceConflict(
                        localMutations = localMutations,
                        remoteMutations = conflictingRemoteMutations
                    )
                } else {
                    null
                }
            }

        val conflictingRemoteIDs = resourceConflicts
            .flatMap { it.remoteMutations }
            .map { it.remoteID }
            .toSet()

        val conflictingLocalIDs = resourceConflicts
            .flatMap { it.localMutations }
            .map { it.localID }
            .toSet()

        return ConflictDetectionResult(
            conflicts = resourceConflicts,
            nonConflictingRemoteMutations = remoteMutations.filterNot { conflictingRemoteIDs.contains(it.remoteID) },
            nonConflictingLocalMutations = localMutations.filterNot { conflictingLocalIDs.contains(it.localID) }
        )
    }

    private fun findConflictingRemoteMutations(
        bookmarkKey: SyncCollectionBookmarkKey,
        localMutations: List<LocalModelMutation<SyncCollectionBookmark>>,
        remoteMutationsByKey: Map<SyncCollectionBookmarkKey, List<RemoteModelMutation<SyncCollectionBookmark>>>,
        remoteMutationsByRemoteID: Map<String, RemoteModelMutation<SyncCollectionBookmark>>
    ): List<RemoteModelMutation<SyncCollectionBookmark>> {
        val remoteMutationsById = localMutations.mapNotNull { it.remoteID }
            .mapNotNull { remoteMutationsByRemoteID[it] }

        return (remoteMutationsByKey[bookmarkKey].orEmpty() + remoteMutationsById).distinct()
    }
}
