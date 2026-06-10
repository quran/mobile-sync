package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.NoteRange
import com.quran.shared.syncengine.model.SyncNote

/**
 * Detects conflicts between local and remote mutations for notes.
 *
 * A conflict is detected whenever local and remote mutations reference the same remote ID. Replayed
 * remote creates also conflict with exactly one pending local create with the same body and range so
 * the create is persisted/backfilled rather than pushed again.
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
        }.toMutableList()

        val conflictingRemoteIds = resourceConflicts
            .flatMap { it.remoteMutations }
            .map { it.remoteID }
            .toMutableSet()

        val conflictingLocalIds = resourceConflicts
            .flatMap { it.localMutations }
            .map { it.localID }
            .toMutableSet()

        remoteMutations
            .filter { it.mutation == Mutation.CREATED && it.remoteID !in conflictingRemoteIds }
            .forEach { remote ->
                val matchingLocals = localMutations.filter { local ->
                    local.localID !in conflictingLocalIds &&
                        local.remoteID == null &&
                        local.mutation == Mutation.CREATED &&
                        local.model.matchesSemanticReplay(remote.model)
                }
                if (matchingLocals.size == 1) {
                    resourceConflicts += ResourceConflict(
                        localMutations = matchingLocals,
                        remoteMutations = listOf(remote)
                    )
                    conflictingLocalIds += matchingLocals.single().localID
                    conflictingRemoteIds += remote.remoteID
                }
            }

        return ConflictDetectionResult(
            conflicts = resourceConflicts,
            nonConflictingRemoteMutations = remoteMutations.filterNot { conflictingRemoteIds.contains(it.remoteID) },
            nonConflictingLocalMutations = localMutations.filterNot { conflictingLocalIds.contains(it.localID) }
        )
    }

    private fun SyncNote.matchesSemanticReplay(remote: SyncNote): Boolean {
        val localRange = singleReplayRangeOrNull() ?: return false
        val remoteRange = remote.singleReplayRangeOrNull() ?: return false
        return body == remote.body &&
            localRange == remoteRange
    }

    private fun SyncNote.singleReplayRangeOrNull(): NoteRange? =
        ranges.singleOrNull()
}
