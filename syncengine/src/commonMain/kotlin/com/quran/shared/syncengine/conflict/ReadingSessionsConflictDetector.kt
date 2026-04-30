package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncReadingSession

/**
 * Detects conflicts between local and remote reading-session mutations.
 *
 * Reading sessions are keyed by remote ID once synced, so conflicts are detected on matching
 * remote IDs. Local mutations without a remote ID are treated as non-conflicting local changes.
 */
class ReadingSessionsConflictDetector(
    private val remoteMutations: List<RemoteModelMutation<SyncReadingSession>>,
    private val localMutations: List<LocalModelMutation<SyncReadingSession>>
) {

    fun getConflicts(): ConflictDetectionResult<SyncReadingSession> {
        val remoteMutationsByRemoteId = remoteMutations.groupBy { it.remoteID }
        val localMutationsByRemoteId = localMutations
            .filter { it.remoteID != null }
            .groupBy { it.remoteID!! }

        val resourceConflicts = localMutationsByRemoteId.mapNotNull { (remoteId, locals) ->
            val remotes = remoteMutationsByRemoteId[remoteId].orEmpty()
            if (remotes.isEmpty()) {
                null
            } else {
                ResourceConflict(
                    localMutations = locals,
                    remoteMutations = remotes
                )
            }
        }

        val conflictingRemoteIds = resourceConflicts
            .flatMap { it.remoteMutations }
            .map { it.remoteID }
            .toSet()

        val conflictingLocalIds = resourceConflicts
            .flatMap { it.localMutations }
            .map { it.localID }
            .toSet()

        return ConflictDetectionResult(
            conflicts = resourceConflicts,
            nonConflictingRemoteMutations = remoteMutations.filterNot { conflictingRemoteIds.contains(it.remoteID) },
            nonConflictingLocalMutations = localMutations.filterNot { conflictingLocalIds.contains(it.localID) }
        )
    }
}
