package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncCollection
import com.quran.shared.syncengine.model.SyncCollectionKey
import com.quran.shared.syncengine.model.conflictKeyOrNull

/**
 * Detects conflicts between local and remote mutations for collections.
 *
 * A conflict is detected whenever a set of local and remote mutations reference the same
 * collection key, or when a remote mutation matches a local remote ID.
 */
class CollectionsConflictDetector(
    private val remoteMutations: List<RemoteModelMutation<SyncCollection>>,
    private val localMutations: List<LocalModelMutation<SyncCollection>>
) {

    fun getConflicts(): ConflictDetectionResult<SyncCollection> {
        val remoteMutationsByKey = remoteMutations
            .mapNotNull { mutation ->
                mutation.model.conflictKeyOrNull()?.let { key -> key to mutation }
            }
            .groupBy({ it.first }, { it.second })
        val remoteMutationsByRemoteID = remoteMutations.associateBy { it.remoteID }

        val resourceConflicts = localMutations
            .groupBy { mutation ->
                mutation.model.conflictKeyOrNull() ?: SyncCollectionKey.LocalId(mutation.localID)
            }
            .mapNotNull { (collectionKey, localMutations) ->
                val conflictingRemoteMutations = findConflictingRemoteMutations(
                    collectionKey,
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
        collectionKey: SyncCollectionKey,
        localMutations: List<LocalModelMutation<SyncCollection>>,
        remoteMutationsByKey: Map<SyncCollectionKey, List<RemoteModelMutation<SyncCollection>>>,
        remoteMutationsByRemoteID: Map<String, RemoteModelMutation<SyncCollection>>
    ): List<RemoteModelMutation<SyncCollection>> {
        val remoteMutationsById = localMutations.mapNotNull { it.remoteID }
            .mapNotNull { remoteMutationsByRemoteID[it] }

        return (remoteMutationsByKey[collectionKey].orEmpty() + remoteMutationsById).distinct()
    }
}
