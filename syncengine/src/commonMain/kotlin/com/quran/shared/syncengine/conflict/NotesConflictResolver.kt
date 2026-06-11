package com.quran.shared.syncengine.conflict

import com.quran.shared.syncengine.model.SyncNote

/**
 * Resolves conflicts between local and remote mutations for notes.
 *
 * Remote mutations normally win, except a local delete for a remote-backed note wins over a
 * replayed remote create echo for the same remote ID.
 */
class NotesConflictResolver(
    private val conflicts: List<ResourceConflict<SyncNote>>
) {

    fun resolve(): ConflictResolutionResult<SyncNote> {
        return resolveRemoteWinsConflicts(conflicts)
    }
}
