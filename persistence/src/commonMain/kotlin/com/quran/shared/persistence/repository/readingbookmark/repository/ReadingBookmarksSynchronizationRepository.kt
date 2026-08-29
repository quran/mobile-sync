package com.quran.shared.persistence.repository.readingbookmark.repository

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.input.LocalSyncReadingBookmark
import com.quran.shared.persistence.input.RemoteReadingBookmark
import com.quran.shared.persistence.repository.PersistenceWriteBoundaryGuard

interface ReadingBookmarksSynchronizationRepository {
    suspend fun fetchMutatedReadingBookmarks(): List<LocalModelMutation<LocalSyncReadingBookmark>>

    suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteReadingBookmark>>,
        localMutationsToClear: List<LocalModelMutation<LocalSyncReadingBookmark>>,
        writeBoundaryGuard: PersistenceWriteBoundaryGuard = PersistenceWriteBoundaryGuard.Allow
    )

    suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean>

    suspend fun fetchReadingBookmarkByRemoteId(remoteId: String): RemoteReadingBookmark?
}
