package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncReadingBookmark

class ReadingBookmarksConflictDetector(
    private val remoteMutations: List<RemoteModelMutation<SyncReadingBookmark>>,
    private val localMutations: List<LocalModelMutation<SyncReadingBookmark>>
) {
    fun getConflicts(): ConflictDetectionResult<SyncReadingBookmark> =
        detectKeyedConflicts(
            remoteMutations = remoteMutations,
            localMutations = localMutations,
            remoteKey = { it.model.slot },
            localKey = { it.model.slot }
        )
}
