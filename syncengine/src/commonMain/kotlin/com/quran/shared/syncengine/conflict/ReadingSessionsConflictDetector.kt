package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncReadingSession

/**
 * Detects conflicts between local and remote reading-session mutations.
 *
 * Reading sessions are keyed by remote ID once synced. Replayed remote creates also conflict with
 * exactly one pending local create at the same position so the local create is not pushed again.
 */
class ReadingSessionsConflictDetector(
    private val remoteMutations: List<RemoteModelMutation<SyncReadingSession>>,
    private val localMutations: List<LocalModelMutation<SyncReadingSession>>
) {

    fun getConflicts(): ConflictDetectionResult<SyncReadingSession> {
        return detectRemoteIdConflicts(remoteMutations, localMutations) { local, remote ->
            local.model.chapterNumber == remote.model.chapterNumber &&
                local.model.verseNumber == remote.model.verseNumber
        }
    }
}
