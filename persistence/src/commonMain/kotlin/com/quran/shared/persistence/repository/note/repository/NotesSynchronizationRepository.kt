package com.quran.shared.persistence.repository.note.repository

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.input.LocalSyncNote
import com.quran.shared.persistence.input.RemoteNote
import com.quran.shared.persistence.repository.PersistenceWriteBoundaryGuard

interface NotesSynchronizationRepository {
    /**
     * Returns a list of notes that have been mutated locally and need to be synchronized.
     */
    suspend fun fetchMutatedNotes(lastModified: Long): List<LocalModelMutation<LocalSyncNote>>

    /**
     * Persists the remote state of notes after a successful synchronization operation.
     */
    suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteNote>>,
        localMutationsToClear: List<LocalModelMutation<LocalSyncNote>>,
        writeBoundaryGuard: PersistenceWriteBoundaryGuard = PersistenceWriteBoundaryGuard.Allow
    )

    suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean>
}
