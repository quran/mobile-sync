package com.quran.shared.syncengine.preprocessing

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncCollectionBookmark
import com.quran.shared.syncengine.model.conflictKey

class CollectionBookmarksRemoteMutationsPreprocessor(
    private val checkLocalExistence: suspend (List<String>) -> Map<String, Boolean>
) {

    /**
     * Preprocesses remote mutations to filter out DELETE mutations for resources that don't exist
     * locally and convert ALL MODIFIED mutations to CREATED mutations.
     */
    suspend fun preprocess(
        remoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
        localMutations: List<LocalModelMutation<SyncCollectionBookmark>> = emptyList()
    ): List<RemoteModelMutation<SyncCollectionBookmark>> {
        val localConflictKeys = localMutations
            .map { mutation -> mutation.model.conflictKey() }
            .toSet()

        return remoteMutations
            .filterDeletesByLocalExistence(checkLocalExistence) { it.model.conflictKey() in localConflictKeys }
            .map { it.mapModifiedToCreated() }
    }
}
