package com.quran.shared.syncengine.preprocessing

import com.quran.shared.mutations.Mutation
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
        val remoteIDsToCheck = remoteMutations.filter { it.mutation == Mutation.DELETED }
            .map { it.remoteID }
        val existenceMap = if (remoteIDsToCheck.isNotEmpty()) checkLocalExistence(remoteIDsToCheck) else emptyMap()

        return remoteMutations
            .filter { it.mutation != Mutation.DELETED || existenceMap[it.remoteID] == true }
            .map { it.mapModified() }
    }
}

private fun <T> RemoteModelMutation<T>.mapModified(): RemoteModelMutation<T> =
    when (this.mutation) {
        Mutation.MODIFIED ->
            RemoteModelMutation(
                model = this.model,
                remoteID = this.remoteID,
                mutation = Mutation.CREATED
            )
        Mutation.DELETED, Mutation.CREATED -> this
    }
