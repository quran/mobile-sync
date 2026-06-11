package com.quran.shared.syncengine

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.conflict.ConflictDetectionResult
import com.quran.shared.syncengine.conflict.ConflictResolutionResult
import com.quran.shared.syncengine.conflict.ReadingSessionsConflictDetector
import com.quran.shared.syncengine.conflict.ReadingSessionsConflictResolver
import com.quran.shared.syncengine.conflict.ResourceConflict
import com.quran.shared.syncengine.model.SyncReadingSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

internal class ReadingSessionsSyncAdapter(
    private val configurations: ReadingSessionsSynchronizationConfigurations
) : SyncResourceAdapter {

    override val resourceName: String = "READING_SESSION"
    override val localModificationDateFetcher: LocalModificationDateFetcher =
        configurations.localModificationDateFetcher

    private val logger = Logger.withTag("ReadingSessionsSyncAdapter")

    override suspend fun buildPlan(
        lastModificationDate: Long,
        remoteMutations: List<SyncMutation>
    ): ResourceSyncPlan {
        val localMutations = configurations.localDataFetcher.fetchLocalMutations(lastModificationDate)
        val parsedRemote = parseRemoteMutations(remoteMutations)

        val conflictDetection = detectConflicts(parsedRemote, localMutations)
        val conflictResolution = resolveConflicts(conflictDetection.conflicts)

        return ReadingSessionsResourceSyncPlan(
            localMutationsToClear = localMutations,
            remoteMutationsToPersist = conflictDetection.nonConflictingRemoteMutations + conflictResolution.mutationsToPersist,
            localMutationsToPush = conflictDetection.nonConflictingLocalMutations + conflictResolution.mutationsToPush
        )
    }

    override suspend fun didFail(message: String) {
        configurations.resultNotifier.didFail(message)
    }

    private fun parseRemoteMutations(
        mutations: List<SyncMutation>
    ): List<RemoteModelMutation<SyncReadingSession>> {
        return mutations.mapSimpleRemoteModelMutations(resourceName) { it.toSyncReadingSession(logger) }
    }

    private fun detectConflicts(
        remote: List<RemoteModelMutation<SyncReadingSession>>,
        local: List<LocalModelMutation<SyncReadingSession>>
    ): ConflictDetectionResult<SyncReadingSession> {
        return ReadingSessionsConflictDetector(remote, local).getConflicts()
    }

    private fun resolveConflicts(
        conflicts: List<ResourceConflict<SyncReadingSession>>
    ): ConflictResolutionResult<SyncReadingSession> {
        return ReadingSessionsConflictResolver(conflicts).resolve()
    }

    private inner class ReadingSessionsResourceSyncPlan(
        private val localMutationsToClear: List<LocalModelMutation<SyncReadingSession>>,
        private val remoteMutationsToPersist: List<RemoteModelMutation<SyncReadingSession>>,
        private val localMutationsToPush: List<LocalModelMutation<SyncReadingSession>>
    ) : ResourceSyncPlan {
        override val resourceName: String = this@ReadingSessionsSyncAdapter.resourceName

        override suspend fun mutationsToPush(): List<SyncMutation> =
            localMutationsToPush.map {
                it.toSyncMutation(
                    resourceName = resourceName,
                    resourceData = SyncReadingSession::toResourceData,
                    timestamp = { model -> model.lastModified.toEpochMilliseconds() }
                )
            }

        override suspend fun complete(newToken: Long, pushedMutations: List<SyncMutation>) {
            val mappedPushed = mapPushedModelMutations(resourceName, localMutationsToPush, pushedMutations)
            val finalRemoteMutations = remoteMutationsToPersist + mappedPushed
            configurations.resultNotifier.didSucceed(
                newToken,
                finalRemoteMutations,
                localMutationsToClear
            )
        }
    }
}

private fun SyncMutation.toSyncReadingSession(logger: Logger): SyncReadingSession? {
    val id = resourceId ?: return null
    val lastModified = Instant.fromEpochMilliseconds(timestamp ?: 0)
    if (mutation == Mutation.DELETED) {
        return SyncReadingSession(
            id = id,
            chapterNumber = 0,
            verseNumber = 0,
            lastModified = lastModified
        )
    }

    val sura = data?.intOrNull("chapterNumber")
    val ayah = data?.intOrNull("verseNumber")

    if (sura == null || ayah == null) {
        logger.w { "Skipping reading session mutation without sura/ayah: resourceId=$id" }
        return null
    }

    return SyncReadingSession(
        id = id,
        chapterNumber = sura,
        verseNumber = ayah,
        lastModified = lastModified
    )
}

private fun SyncReadingSession.toResourceData(): JsonObject {
    return buildJsonObject {
        put("chapterNumber", chapterNumber)
        put("verseNumber", verseNumber)
    }
}
