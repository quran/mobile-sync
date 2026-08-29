package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.syncengine.model.SyncReadingBookmark

class ReadingBookmarksConflictResolver(
    private val conflicts: List<ResourceConflict<SyncReadingBookmark>>
) {
    fun resolve(): ConflictResolutionResult<SyncReadingBookmark> = conflicts
        .map { conflict ->
            val remote = conflict.remoteMutations.maxBy { it.model.lastModified }
            val local = conflict.localMutations.maxBy { it.model.lastModified }
            if (local.model.lastModified > remote.model.lastModified) {
                pushLocalMutation(local.targeting(remote.remoteID))
            } else {
                persistRemoteMutation(remote)
            }
        }
        .mergeConflictResolutionResults()

    private fun LocalModelMutation<SyncReadingBookmark>.targeting(
        canonicalRemoteId: String
    ): LocalModelMutation<SyncReadingBookmark> = LocalModelMutation(
        model = model,
        remoteID = remoteID ?: canonicalRemoteId,
        localID = localID,
        mutation = Mutation.MODIFIED,
        ack = ack
    )
}
