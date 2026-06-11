package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncCollectionBookmark
import com.quran.shared.syncengine.model.conflictKey

/**
 * Detects conflicts between local and remote mutations for collection bookmarks.
 *
 * A conflict is detected whenever a set of local and remote mutations reference the same
 * collection-bookmark key, or when a remote mutation matches a local remote ID.
 */
class CollectionBookmarksConflictDetector(
    private val remoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
    private val localMutations: List<LocalModelMutation<SyncCollectionBookmark>>
) {

    fun getConflicts(): ConflictDetectionResult<SyncCollectionBookmark> {
        return detectKeyedConflicts(
            remoteMutations = remoteMutations,
            localMutations = localMutations,
            remoteKey = { it.model.conflictKey() },
            localKey = { it.model.conflictKey() }
        )
    }
}
