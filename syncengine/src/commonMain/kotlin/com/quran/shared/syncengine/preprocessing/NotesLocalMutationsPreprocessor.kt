package com.quran.shared.syncengine.preprocessing

import com.quran.shared.mutations.LocalModelMutation
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
        localMutations.requireRemoteBackedDeletes("Note")
        return localMutations
    }
}
