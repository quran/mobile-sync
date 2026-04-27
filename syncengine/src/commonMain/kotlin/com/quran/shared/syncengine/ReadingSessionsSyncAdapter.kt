package com.quran.shared.syncengine

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.conflict.ConflictResolutionResult
import com.quran.shared.syncengine.conflict.ConflictDetectionResult
import com.quran.shared.syncengine.conflict.ResourceConflict
import com.quran.shared.syncengine.model.SyncReadingSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
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

        // Reading sessions usually don't need complex preprocessing like bookmarks
        val conflictDetection = detectConflicts(parsedRemote, localMutations)
        val conflictResolution = resolveConflicts(conflictDetection.conflicts)

        val mutationsToPush = conflictDetection.nonConflictingLocalMutations + conflictResolution.mutationsToPush
        val mutationsToPersist = conflictDetection.nonConflictingRemoteMutations + conflictResolution.mutationsToPersist

        return ReadingSessionsResourceSyncPlan(
            localMutationsToClear = localMutations,
            remoteMutationsToPersist = mutationsToPersist,
            localMutationsToPush = mutationsToPush
        )
    }

    override suspend fun didFail(message: String) {
        configurations.resultNotifier.didFail(message)
    }

    private suspend fun parseRemoteMutations(
        mutations: List<SyncMutation>
    ): List<RemoteModelMutation<SyncReadingSession>> {
        return mutations.mapNotNull { mutation ->
            if (!mutation.resource.equals(resourceName, ignoreCase = true)) {
                return@mapNotNull null
            }
            val resourceId = mutation.resourceId ?: return@mapNotNull null
            val session = mutation.toSyncReadingSession(logger) ?: return@mapNotNull null
            RemoteModelMutation(
                model = session,
                remoteID = resourceId,
                mutation = mutation.mutation
            )
        }
    }

    private fun toSyncMutation(localMutation: LocalModelMutation<SyncReadingSession>): SyncMutation {
        return SyncMutation(
            resource = resourceName,
            resourceId = localMutation.remoteID,
            mutation = localMutation.mutation,
            data = if (localMutation.mutation == Mutation.DELETED) null else localMutation.model.toResourceData(),
            timestamp = null
        )
    }

    private fun detectConflicts(
        remote: List<RemoteModelMutation<SyncReadingSession>>,
        local: List<LocalModelMutation<SyncReadingSession>>
    ): ConflictDetectionResult<SyncReadingSession> {
        // Reading sessions are usually last-write-wins by modified_at
        val conflictDetector = ConflictDetector(remote, local)
        return conflictDetector.getConflicts()
    }

    private fun resolveConflicts(
        conflicts: List<ResourceConflict<SyncReadingSession>>
    ): ConflictResolutionResult<SyncReadingSession> {
        val resolver = ConflictResolver(conflicts)
        return resolver.resolve()
    }

    private fun mapPushedMutations(
        localMutations: List<LocalModelMutation<SyncReadingSession>>,
        pushedMutations: List<SyncMutation>
    ): List<RemoteModelMutation<SyncReadingSession>> {
        return localMutations.mapIndexed { index, localMutation ->
            val pushedMutation = pushedMutations[index]
            RemoteModelMutation(
                model = localMutation.model,
                remoteID = pushedMutation.resourceId!!,
                mutation = pushedMutation.mutation
            )
        }
    }

    private inner class ReadingSessionsResourceSyncPlan(
        private val localMutationsToClear: List<LocalModelMutation<SyncReadingSession>>,
        private val remoteMutationsToPersist: List<RemoteModelMutation<SyncReadingSession>>,
        private val localMutationsToPush: List<LocalModelMutation<SyncReadingSession>>
    ) : ResourceSyncPlan {
        override val resourceName: String = this@ReadingSessionsSyncAdapter.resourceName

        override fun mutationsToPush(): List<SyncMutation> {
            return localMutationsToPush.map { toSyncMutation(it) }
        }

        override suspend fun complete(newToken: Long, pushedMutations: List<SyncMutation>) {
            val mappedPushed = mapPushedMutations(localMutationsToPush, pushedMutations)
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
    val sura = data?.intOrNull("sura") ?: data?.intOrNull("sura_number")
    val ayah = data?.intOrNull("ayah") ?: data?.intOrNull("verse_number")
    
    if (sura == null || ayah == null) {
        logger.w { "Skipping reading session mutation without sura/ayah: resourceId=$id" }
        return null
    }

    return SyncReadingSession(
        id = id,
        page = 0, // Page is local convenience, not synced
        chapterNumber = sura,
        verseNumber = ayah,
        lastModified = Instant.fromEpochMilliseconds(timestamp ?: 0)
    )
}

private fun SyncReadingSession.toResourceData(): JsonObject {
    return buildJsonObject {
        put("chapterNumber", chapterNumber)
        put("verseNumber", verseNumber)
    }
}

private fun JsonObject.intOrNull(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull
