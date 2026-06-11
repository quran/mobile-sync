package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncCollection
import com.quran.shared.syncengine.model.SyncCollectionKey
import com.quran.shared.syncengine.model.conflictKeyOrNull

/**
 * Detects conflicts between local and remote mutations for collections.
 *
 * A conflict is detected whenever a set of local and remote mutations reference the same
 * collection key, or when a remote mutation matches a local remote ID.
 */
class CollectionsConflictDetector(
    private val remoteMutations: List<RemoteModelMutation<SyncCollection>>,
    private val localMutations: List<LocalModelMutation<SyncCollection>>
) {

    fun getConflicts(): ConflictDetectionResult<SyncCollection> {
        return detectKeyedConflicts(
            remoteMutations = remoteMutations,
            localMutations = localMutations,
            remoteKey = { it.model.conflictKeyOrNull() },
            localKey = { it.model.conflictKeyOrNull() ?: SyncCollectionKey.LocalId(it.localID) }
        )
    }
}
