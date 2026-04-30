package com.quran.shared.syncengine.conflict

import com.quran.shared.syncengine.model.SyncReadingSession

/**
 * Resolves reading-session conflicts using last-write-wins semantics.
 *
 * The newest mutation by [SyncReadingSession.lastModified] wins. Ties prefer the remote side so
 * that already-synced data remains authoritative when timestamps match.
 */
class ReadingSessionsConflictResolver(
    private val conflicts: List<ResourceConflict<SyncReadingSession>>
) {

    fun resolve(): ConflictResolutionResult<SyncReadingSession> {
        if (conflicts.isEmpty()) {
            return ConflictResolutionResult(listOf(), listOf())
        }

        return conflicts.fold(
            ConflictResolutionResult(
                mutationsToPersist = listOf(),
                mutationsToPush = listOf()
            )
        ) { accumulator, conflict ->
            val resolved = processConflict(conflict)
            ConflictResolutionResult(
                mutationsToPersist = accumulator.mutationsToPersist + resolved.mutationsToPersist,
                mutationsToPush = accumulator.mutationsToPush + resolved.mutationsToPush
            )
        }
    }

    private fun processConflict(resourceConflict: ResourceConflict<SyncReadingSession>): ConflictResolutionResult<SyncReadingSession> {
        val newestRemote = resourceConflict.remoteMutations.maxByOrNull { it.model.lastModified }!!
        val newestLocal = resourceConflict.localMutations.maxByOrNull { it.model.lastModified }!!

        return if (newestLocal.model.lastModified > newestRemote.model.lastModified) {
            ConflictResolutionResult(
                mutationsToPersist = listOf(),
                mutationsToPush = listOf(newestLocal)
            )
        } else {
            ConflictResolutionResult(
                mutationsToPersist = listOf(newestRemote),
                mutationsToPush = listOf()
            )
        }
    }
}
