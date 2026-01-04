package com.quran.shared.persistence.repository.bookmark.repository

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.input.RemoteBookmark
import com.quran.shared.persistence.model.Bookmark

interface BookmarksSynchronizationRepository {
    /**
     * Returns a list of bookmarks that have been mutated locally (created or deleted)
     * and need to be synchronized with the remote server.
     */
    suspend fun fetchMutatedBookmarks(): List<LocalModelMutation<Bookmark>>

    /**
     * Persists the remote state of bookmarks after a successful synchronization operation.
     * This method should be called after the remote server has confirmed the changes.
     *
     * @param updatesToPersist List of remote bookmark inputs with their remote IDs and mutation
     * states to be persisted. These must have a remoteID setup.
     * @param localMutationsToClear List of local mutations to be cleared. An item of this list
     * denotes either a mutation that was committed remotely, or a mutation that overridden. If it
     * was committed, a counterpart is expected in `updatesToPersists` to persist it as a remote
     * bookmark. These must be input from the list returned by `fetchMutatedBookmarks`.
     */
    suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteBookmark>>,
        localMutationsToClear: List<LocalModelMutation<Bookmark>>
    )

    suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean>
}
