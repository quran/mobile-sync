package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation

data class ConflictDetectionResult<Model>(
    val conflicts: List<ModelConflict<Model>>,
    val nonConflictingRemoteMutations: List<RemoteModelMutation<Model>>,
    val nonConflictingLocalMutations: List<LocalModelMutation<Model>>
)

class ConflictDetector(
    val remoteModelMutations: List<RemoteModelMutation<PageBookmark>>,
    val localModelMutations: List<LocalModelMutation<PageBookmark>>
    ) {

    fun getConflicts(): ConflictDetectionResult<PageBookmark> {
        TODO()
    }
}