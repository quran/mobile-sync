package com.quran.shared.syncengine.conflict

import com.quran.shared.syncengine.model.SyncReadingSession

/**
 * Resolves reading-session conflicts using last-write-wins semantics.
 *
 * The newest mutation by [SyncReadingSession.lastModified] wins. Ties prefer the remote side so
 * that already-synced data remains authoritative when timestamps match, including a replayed create's echo.
 */
class ReadingSessionsConflictResolver(
    private val conflicts: List<ResourceConflict<SyncReadingSession>>
) {

    fun resolve(): ConflictResolutionResult<SyncReadingSession> {
        return conflicts
            .map { processConflict(it) }
            .mergeConflictResolutionResults()
    }

    private fun processConflict(resourceConflict: ResourceConflict<SyncReadingSession>): ConflictResolutionResult<SyncReadingSession> {
        resourceConflict.resolveLocalDeleteOverRemoteCreateEcho()?.let { return it }

        val newestRemote = resourceConflict.remoteMutations.maxByOrNull { it.model.lastModified }!!
        val newestLocal = resourceConflict.localMutations.maxByOrNull { it.model.lastModified }!!

        return if (newestLocal.model.lastModified > newestRemote.model.lastModified) {
            pushLocalMutation(newestLocal)
        } else {
            persistRemoteMutation(newestRemote)
        }
    }
}
