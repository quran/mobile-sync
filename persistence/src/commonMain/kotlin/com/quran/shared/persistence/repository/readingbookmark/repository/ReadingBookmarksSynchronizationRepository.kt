package com.quran.shared.persistence.repository.readingbookmark.repository

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.input.RemoteBookmark
import com.quran.shared.persistence.model.ReadingBookmark

interface ReadingBookmarksSynchronizationRepository {
    suspend fun fetchMutatedReadingBookmarks(): List<LocalModelMutation<ReadingBookmark>>

    suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteBookmark>>,
        localMutationsToClear: List<LocalModelMutation<ReadingBookmark>>
    )

    suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean>

    suspend fun fetchReadingBookmarkByRemoteId(remoteId: String): ReadingBookmark?
}
