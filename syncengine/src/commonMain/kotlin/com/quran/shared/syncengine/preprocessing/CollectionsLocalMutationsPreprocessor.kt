package com.quran.shared.syncengine.preprocessing

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.syncengine.model.SyncCollection

class CollectionsLocalMutationsPreprocessor {

    /**
     * Validates local mutations for collections.
     *
     * Ensures deletions always reference a remote ID.
     */
    fun preprocess(
        localMutations: List<LocalModelMutation<SyncCollection>>
    ): List<LocalModelMutation<SyncCollection>> {
        localMutations.requireRemoteBackedDeletes("Collection")
        return localMutations
    }
}
