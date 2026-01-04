package com.quran.shared.persistence.repository.collection.repository

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.model.Collection

interface CollectionsSynchronizationRepository {
    /**
     * Returns a list of collections that have been mutated locally (created, modified, or deleted)
     * and need to be synchronized with the remote server.
     */
    suspend fun fetchMutatedCollections(): List<LocalModelMutation<Collection>>

    /**
     * Persists the remote state of collections after a successful synchronization operation.
     * This method should be called after the remote server has confirmed the changes.
     *
     * @param updatesToPersist List of collections with their remote IDs and mutation states to be
     * persisted. These must have a remoteID setup.
     * @param localMutationsToClear List of local mutations to be cleared. An item of this list
     * denotes either a mutation that was committed remotely, or a mutation that overridden. If it
     * was committed, a counterpart is expected in `updatesToPersist` to persist it as a remote
     * collection. These must be input from the list returned by `fetchMutatedCollections`.
     */
    suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<Collection>>,
        localMutationsToClear: List<LocalModelMutation<Collection>>
    )

    suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean>
}
