package com.quran.shared.syncengine.preprocessing

import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncBookmark

class BookmarksRemoteMutationsPreprocessor(
    private val checkLocalExistence: suspend (List<String>) -> Map<String, Boolean>
) {
    
    /**
     * Preprocesses remote mutations to filter out DELETE mutations for resources that don't exist
     * locally and convert ALL MODIFIED mutations to CREATED mutations.
     *
     * @param remoteMutations List of remote mutations to preprocess
     * @return Filtered and transformed list of remote mutations
     */
    suspend fun preprocess(remoteMutations: List<RemoteModelMutation<SyncBookmark>>): List<RemoteModelMutation<SyncBookmark>> {
        return remoteMutations
            .filterDeletesByLocalExistence(checkLocalExistence)
            .map { it.mapModifiedToCreated() }
    }
}
