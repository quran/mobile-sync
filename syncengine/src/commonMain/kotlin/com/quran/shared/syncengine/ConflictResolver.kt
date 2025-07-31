package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation

data class ConflictResolutionResult<Model>(
    val mutationsToPersist: List<RemoteModelMutation<Model>>,
    val mutationsToPush: List<LocalModelMutation<Model>>
)

class ConflictResolver(val conflictGroups: List<ConflictGroup<PageBookmark>>) {

    fun resolve(): ConflictResolutionResult<PageBookmark> {
        return ConflictResolutionResult(mutationsToPersist = listOf(), mutationsToPush = listOf())
    }
}