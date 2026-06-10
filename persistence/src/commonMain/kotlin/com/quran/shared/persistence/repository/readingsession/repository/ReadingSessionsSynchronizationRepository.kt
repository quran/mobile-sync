package com.quran.shared.persistence.repository.readingsession.repository

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.input.RemoteReadingSession
import com.quran.shared.persistence.model.ReadingSession
import com.quran.shared.persistence.repository.PersistenceWriteBoundaryGuard

interface ReadingSessionsSynchronizationRepository {
    suspend fun fetchMutatedReadingSessions(): List<LocalModelMutation<ReadingSession>>
    suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteReadingSession>>,
        localMutationIdsToClear: List<String>,
        writeBoundaryGuard: PersistenceWriteBoundaryGuard = PersistenceWriteBoundaryGuard.Allow
    )
    suspend fun applyRemoteChangesForMutations(
        updatesToPersist: List<RemoteModelMutation<RemoteReadingSession>>,
        localMutationsToClear: List<LocalModelMutation<ReadingSession>>,
        writeBoundaryGuard: PersistenceWriteBoundaryGuard = PersistenceWriteBoundaryGuard.Allow
    )
    suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean>
    suspend fun fetchReadingSessionByRemoteId(remoteId: String): ReadingSession?
}
