package com.quran.shared.persistence.repository.collectionbookmark.repository

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.input.RemoteCollectionBookmark
import com.quran.shared.persistence.model.CollectionBookmark

interface CollectionBookmarksSynchronizationRepository {
    /**
     * Returns a list of collection bookmarks that have been mutated locally
     * and need to be synchronized with the remote server.
     */
    suspend fun fetchMutatedCollectionBookmarks(): List<LocalModelMutation<CollectionBookmark>>

    /**
     * Persists the remote state of collection bookmarks after a successful synchronization operation.
     *
     * @param updatesToPersist List of remote collection bookmarks with their remote IDs and mutation
     * states to be persisted. These must have a remoteID setup.
     * @param localMutationsToClear List of local mutations to be cleared. An item of this list
     * denotes either a mutation that was committed remotely, or a mutation that was overridden.
     */
    suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteCollectionBookmark>>,
        localMutationsToClear: List<LocalModelMutation<CollectionBookmark>>
    )

    suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean>
}
