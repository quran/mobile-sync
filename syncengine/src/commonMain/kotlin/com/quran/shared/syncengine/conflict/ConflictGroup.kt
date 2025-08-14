package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.PageBookmark

/**
 * Represents a group of conflicting local and remote mutations for the same resource.
 */
data class ConflictGroup<Model>(
    val localMutations: List<LocalModelMutation<Model>>,
    val remoteMutations: List<RemoteModelMutation<Model>>
) 