package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
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
        return detectRemoteIdConflicts(remoteMutations, localMutations) { local, remote ->
            local.model.matchesSemanticReplay(remote.model)
        }
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
