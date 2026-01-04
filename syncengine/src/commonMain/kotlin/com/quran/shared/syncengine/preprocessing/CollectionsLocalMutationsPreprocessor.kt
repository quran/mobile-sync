package com.quran.shared.syncengine.preprocessing

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
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
        localMutations.forEach { mutation ->
            if (mutation.mutation == Mutation.DELETED && mutation.remoteID == null) {
                throw IllegalArgumentException(
                    "Collection deletion without remote ID is not allowed. " +
                        "Mutation: ${mutation.mutation}(${mutation.localID})"
                )
            }
        }

        return localMutations
    }
}
