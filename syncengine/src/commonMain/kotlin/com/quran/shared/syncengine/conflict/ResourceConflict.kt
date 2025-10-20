package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation

/**
 * Represents a group of conflicting local and remote mutations for the same resource.
 */
data class ResourceConflict<Model>(
    val localMutations: List<LocalModelMutation<Model>>,
    val remoteMutations: List<RemoteModelMutation<Model>>
) 