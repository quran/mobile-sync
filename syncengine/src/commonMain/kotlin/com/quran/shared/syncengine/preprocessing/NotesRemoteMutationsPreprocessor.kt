package com.quran.shared.syncengine.preprocessing

import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncNote

class NotesRemoteMutationsPreprocessor(
    private val checkLocalExistence: suspend (List<String>) -> Map<String, Boolean>
) {

    /**
     * Preprocesses remote mutations to filter out DELETE mutations for resources that don't exist
     * locally.
     */
    suspend fun preprocess(
        remoteMutations: List<RemoteModelMutation<SyncNote>>
    ): List<RemoteModelMutation<SyncNote>> {
        val remoteIDsToCheck = remoteMutations.filter { it.mutation == Mutation.DELETED }
            .map { it.remoteID }
        val existenceMap = if (remoteIDsToCheck.isNotEmpty()) {
            checkLocalExistence(remoteIDsToCheck)
        } else {
            emptyMap()
        }

        return remoteMutations
            .filter { it.mutation != Mutation.DELETED || existenceMap[it.remoteID] == true }
    }
}
