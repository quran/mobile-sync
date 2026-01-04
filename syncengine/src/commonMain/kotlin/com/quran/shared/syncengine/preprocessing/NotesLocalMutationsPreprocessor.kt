package com.quran.shared.syncengine.preprocessing

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.syncengine.model.SyncNote

class NotesLocalMutationsPreprocessor {

    /**
     * Validates local mutations for notes.
     *
     * Ensures deletions always reference a remote ID.
     */
    fun preprocess(
        localMutations: List<LocalModelMutation<SyncNote>>
    ): List<LocalModelMutation<SyncNote>> {
        localMutations.forEach { mutation ->
            if (mutation.mutation == Mutation.DELETED && mutation.remoteID == null) {
                throw IllegalArgumentException(
                    "Note deletion without remote ID is not allowed. " +
                        "Mutation: ${mutation.mutation}(${mutation.localID})"
                )
            }
        }

        return localMutations
    }
}
