package com.quran.shared.persistence.repository.recentpage.repository

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.input.RemoteReadingSession
import com.quran.shared.persistence.model.RecentPage

interface RecentPagesSynchronizationRepository {
    suspend fun fetchMutatedRecentPages(): List<LocalModelMutation<RecentPage>>
    suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteReadingSession>>,
        localMutationIdsToClear: List<String>
    )
    suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean>
    suspend fun fetchRecentPageByRemoteId(remoteId: String): RecentPage?
}
