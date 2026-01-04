package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncNote

/**
 * Detects conflicts between local and remote mutations for notes.
 *
 * A conflict is detected whenever local and remote mutations reference the same remote ID.
 */
class NotesConflictDetector(
    private val remoteMutations: List<RemoteModelMutation<SyncNote>>,
    private val localMutations: List<LocalModelMutation<SyncNote>>
) {

    fun getConflicts(): ConflictDetectionResult<SyncNote> {
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
