package com.quran.shared.syncengine

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.conflict.ConflictDetectionResult
import com.quran.shared.syncengine.conflict.ConflictResolutionResult
import com.quran.shared.syncengine.conflict.ReadingBookmarksConflictDetector
import com.quran.shared.syncengine.conflict.ReadingBookmarksConflictResolver
import com.quran.shared.syncengine.conflict.ResourceConflict
import com.quran.shared.syncengine.model.SyncReadingBookmark
import com.quran.shared.syncengine.model.SyncReadingBookmarkLocation
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ReadingBookmarksSyncAdapter(
    private val configurations: ReadingBookmarksSynchronizationConfigurations
) : SyncResourceAdapter {

    override val resourceName: String = "READING_BOOKMARK"
    override val localModificationDateFetcher: LocalModificationDateFetcher =
        configurations.localModificationDateFetcher

    private val logger = Logger.withTag("ReadingBookmarksSyncAdapter")

    override suspend fun buildPlan(
        lastModificationDate: Long,
        remoteMutations: List<SyncMutation>
    ): ResourceSyncPlan {
        val local = configurations.localDataFetcher.fetchLocalMutations(lastModificationDate)
        val remote = parseRemoteMutations(remoteMutations)
        val detection = detectConflicts(remote, local)
        val resolution = resolveConflicts(detection.conflicts)
        return ReadingBookmarksResourceSyncPlan(
            localMutationsToClear = local,
            remoteMutationsToPersist = detection.nonConflictingRemoteMutations + resolution.mutationsToPersist,
            localMutationsToPush = detection.nonConflictingLocalMutations + resolution.mutationsToPush
        )
    }

    override suspend fun didFail(message: String) {
        configurations.resultNotifier.didFail(message)
    }

    private suspend fun parseRemoteMutations(
        mutations: List<SyncMutation>
    ): List<RemoteModelMutation<SyncReadingBookmark>> = mutations.mapNotNull { mutation ->
        if (!mutation.resource.equals(resourceName, ignoreCase = true)) {
            return@mapNotNull null
        }
        val remoteId = mutation.requireSimpleResourceRemoteId(resourceName)
        val explicitType = mutation.data.stringOrNull("type")?.uppercase()
        if (explicitType != null && explicitType !in setOf("AYAH", "PAGE")) {
            logger.w { "Skipping reading bookmark mutation with unsupported type=$explicitType: remoteId=$remoteId" }
            return@mapNotNull null
        }
        val parsedModel = mutation.toSyncReadingBookmark()
        val model = parsedModel ?: if (explicitType == null) {
            configurations.localDataFetcher.fetchLocalModel(remoteId)?.copy(
                location = null,
                lastModified = mutation.clientUpdatedAtInstant()
            )
        } else {
            null
        }
        if (model == null) {
            logger.w { "Skipping reading bookmark mutation with an invalid slot or location: remoteId=$remoteId" }
            return@mapNotNull null
        }
        RemoteModelMutation(
            model = model,
            remoteID = remoteId,
            mutation = mutation.mutation
        )
    }

    private fun detectConflicts(
        remote: List<RemoteModelMutation<SyncReadingBookmark>>,
        local: List<LocalModelMutation<SyncReadingBookmark>>
    ): ConflictDetectionResult<SyncReadingBookmark> =
        ReadingBookmarksConflictDetector(remote, local).getConflicts()

    private fun resolveConflicts(
        conflicts: List<ResourceConflict<SyncReadingBookmark>>
    ): ConflictResolutionResult<SyncReadingBookmark> =
        ReadingBookmarksConflictResolver(conflicts).resolve()

    private inner class ReadingBookmarksResourceSyncPlan(
        private val localMutationsToClear: List<LocalModelMutation<SyncReadingBookmark>>,
        private val remoteMutationsToPersist: List<RemoteModelMutation<SyncReadingBookmark>>,
        private val localMutationsToPush: List<LocalModelMutation<SyncReadingBookmark>>
    ) : ResourceSyncPlan {
        override val resourceName: String = this@ReadingBookmarksSyncAdapter.resourceName

        override suspend fun mutationsToPush(): List<SyncMutation> = localMutationsToPush.map { local ->
            local.toSyncMutation(
                resourceName = resourceName,
                resourceData = SyncReadingBookmark::toResourceData,
                timestamp = { it.lastModified.toEpochMilliseconds() },
                createdTimestamp = { it.createdAt?.toEpochMilliseconds() }
            )
        }

        override suspend fun complete(newToken: Long, pushedMutations: List<SyncMutation>) {
            val pushed = mapPushedModelMutations(resourceName, localMutationsToPush, pushedMutations)
            configurations.resultNotifier.didSucceed(
                newToken,
                remoteMutationsToPersist + pushed,
                localMutationsToClear
            )
        }
    }
}

private fun SyncMutation.toSyncReadingBookmark(): SyncReadingBookmark? {
    val slot = data.intOrNull("slot")?.takeIf { it in 1..3 } ?: return null
    val type = data.stringOrNull("type")?.uppercase()
    val mushafId = data.intOrNull("mushafId")
    val key = data.intOrNull("key")
    val location = when (type) {
        "AYAH" -> {
            val ayah = data.intOrNull("verseNumber")
            if (key != null && ayah != null) {
                SyncReadingBookmarkLocation.Ayah(key, ayah)
            } else {
                null
            }
        }
        "PAGE" -> if (mushafId == SUPPORTED_MUSHAF_ID && key != null) {
            SyncReadingBookmarkLocation.Page(key)
        } else {
            null
        }
        else -> null
    }
    if (type != null && location == null) return null
    return SyncReadingBookmark(
        slot = slot,
        name = data.stringOrNull("name"),
        location = location,
        lastModified = clientUpdatedAtInstant(),
        createdAt = clientCreatedAtInstant()
    )
}

private fun SyncReadingBookmark.toResourceData(): JsonObject = buildJsonObject {
    put("slot", slot)
    if (name == null) put("name", JsonNull) else put("name", name)
    when (val value = location) {
        is SyncReadingBookmarkLocation.Ayah -> {
            put("type", "ayah")
            put("key", value.sura)
            put("verseNumber", value.ayah)
            put("mushafId", SUPPORTED_MUSHAF_ID)
        }
        is SyncReadingBookmarkLocation.Page -> {
            put("type", "page")
            put("key", value.page)
            put("mushafId", SUPPORTED_MUSHAF_ID)
        }
        null -> {
            put("type", JsonNull)
            put("key", JsonNull)
            put("verseNumber", JsonNull)
            put("mushafId", JsonNull)
        }
    }
}

private const val SUPPORTED_MUSHAF_ID = 1
