package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncReadingSession

/**
 * Detects conflicts between local and remote reading-session mutations.
 *
 * Reading sessions are keyed by remote ID once synced. Replayed remote creates also conflict with
 * exactly one pending local create at the same position so the local create is not pushed again.
 */
class ReadingSessionsConflictDetector(
    private val remoteMutations: List<RemoteModelMutation<SyncReadingSession>>,
    private val localMutations: List<LocalModelMutation<SyncReadingSession>>
) {

    fun getConflicts(): ConflictDetectionResult<SyncReadingSession> {
        val remoteMutationsByRemoteId = remoteMutations.groupBy { it.remoteID }
        val localMutationsByRemoteId = localMutations
            .filter { it.remoteID != null }
            .groupBy { it.remoteID!! }

        val resourceConflicts = localMutationsByRemoteId.mapNotNull { (remoteId, locals) ->
            val remotes = remoteMutationsByRemoteId[remoteId].orEmpty()
            if (remotes.isEmpty()) {
                null
            } else {
                ResourceConflict(
                    localMutations = locals,
                    remoteMutations = remotes
                )
            }
        }.toMutableList()

        val conflictingRemoteIds = resourceConflicts
            .flatMap { it.remoteMutations }
            .map { it.remoteID }
            .toMutableSet()

        val conflictingLocalIds = resourceConflicts
            .flatMap { it.localMutations }
            .map { it.localID }
            .toMutableSet()

        remoteMutations
            .filter { it.mutation == Mutation.CREATED && it.remoteID !in conflictingRemoteIds }
            .forEach { remote ->
                val matchingLocals = localMutations.filter { local ->
                    local.localID !in conflictingLocalIds &&
                        local.remoteID == null &&
                        local.mutation == Mutation.CREATED &&
                        local.model.chapterNumber == remote.model.chapterNumber &&
                        local.model.verseNumber == remote.model.verseNumber
                }
                if (matchingLocals.size == 1) {
                    resourceConflicts += ResourceConflict(
                        localMutations = matchingLocals,
                        remoteMutations = listOf(remote)
                    )
                    conflictingLocalIds += matchingLocals.single().localID
                    conflictingRemoteIds += remote.remoteID
                }
            }

        return ConflictDetectionResult(
            conflicts = resourceConflicts,
            nonConflictingRemoteMutations = remoteMutations.filterNot { conflictingRemoteIds.contains(it.remoteID) },
            nonConflictingLocalMutations = localMutations.filterNot { conflictingLocalIds.contains(it.localID) }
        )
    }
}
