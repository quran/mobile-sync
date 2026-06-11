package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncBookmark
import com.quran.shared.syncengine.model.conflictKey
import com.quran.shared.syncengine.model.conflictKeyOrNull

/**
 * Detects conflicts between local and remote mutations for bookmarks.
 *
 * A conflict is detected whenever a set of local and remote mutations reference the same bookmark
 * key, or when a remote deletion is missing resource data but matches a local remote ID.
 *
 * The detector groups conflicts by bookmark key and provides separate lists for non-conflicting mutations.
 */
class ConflictDetector(
    private val remoteMutations: List<RemoteModelMutation<SyncBookmark>>,
    private val localMutations: List<LocalModelMutation<SyncBookmark>>
) {

    fun getConflicts(): ConflictDetectionResult<SyncBookmark> {
        return detectKeyedConflicts(
            remoteMutations = remoteMutations,
            localMutations = localMutations,
            remoteKey = { it.model.conflictKeyOrNull() },
            localKey = { it.model.conflictKey() }
        )
    }
}
