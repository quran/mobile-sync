package com.quran.shared.syncengine

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.conflict.ConflictDetector
import com.quran.shared.syncengine.conflict.ConflictResolutionResult
import com.quran.shared.syncengine.conflict.ConflictResolver
import com.quran.shared.syncengine.conflict.ConflictDetectionResult
import com.quran.shared.syncengine.conflict.ResourceConflict
import com.quran.shared.syncengine.model.SyncBookmark
import com.quran.shared.syncengine.preprocessing.LocalMutationsPreprocessor
import com.quran.shared.syncengine.preprocessing.RemoteMutationsPreprocessor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Instant

internal class BookmarksSyncAdapter(
    private val configurations: BookmarksSynchronizationConfigurations
) : SyncResourceAdapter {

    override val resourceName: String = "BOOKMARK"
    override val localModificationDateFetcher: LocalModificationDateFetcher =
        configurations.localModificationDateFetcher

    private val logger = Logger.withTag("BookmarksSyncAdapter")

    override suspend fun buildPlan(
        lastModificationDate: Long,
        remoteMutations: List<SyncMutation>
    ): ResourceSyncPlan {
        val localMutations = configurations.localDataFetcher.fetchLocalMutations(lastModificationDate)
        logger.i {
            "Local data fetched for $resourceName: " +
                "lastModificationDate=$lastModificationDate, localMutations=${localMutations.size}"
        }
        val preprocessedLocal = preprocessLocalMutations(localMutations)
        logger.d {
            "Local mutations preprocessed for $resourceName: " +
                "${localMutations.size} -> ${preprocessedLocal.size}"
        }

        val parsedRemote = parseRemoteMutations(remoteMutations)
        val preprocessedRemote = preprocessRemoteMutations(parsedRemote)
        logger.d {
            "Remote mutations preprocessed for $resourceName: " +
                "${parsedRemote.size} -> ${preprocessedRemote.size}"
        }

        val conflictDetection = detectConflicts(preprocessedRemote, preprocessedLocal)
        logger.d {
            "Conflict detection for $resourceName: " +
                "conflicts=${conflictDetection.conflicts.size}, " +
                "nonConflictingLocal=${conflictDetection.nonConflictingLocalMutations.size}, " +
                "nonConflictingRemote=${conflictDetection.nonConflictingRemoteMutations.size}"
        }

        val conflictResolution = resolveConflicts(conflictDetection.conflicts)
        logger.d {
            "Conflict resolution for $resourceName: " +
                "persist=${conflictResolution.mutationsToPersist.size}, " +
                "push=${conflictResolution.mutationsToPush.size}"
        }

        val mutationsToPush = conflictDetection.nonConflictingLocalMutations + conflictResolution.mutationsToPush
        val mutationsToPersist = conflictDetection.nonConflictingRemoteMutations + conflictResolution.mutationsToPersist

        return BookmarksResourceSyncPlan(
            localMutationsToClear = preprocessedLocal,
            remoteMutationsToPersist = mutationsToPersist,
            localMutationsToPush = mutationsToPush
        )
    }

    override suspend fun didFail(message: String) {
        configurations.resultNotifier.didFail(message)
    }

    private fun parseRemoteMutations(
        mutations: List<SyncMutation>
    ): List<RemoteModelMutation<SyncBookmark>> {
        return mutations.mapNotNull { mutation ->
            if (!mutation.resource.equals(resourceName, ignoreCase = true)) {
                return@mapNotNull null
            }
            val resourceId = mutation.resourceId
            if (resourceId == null) {
                logger.w { "Skipping bookmark mutation without resourceId" }
                return@mapNotNull null
            }
            val bookmark = mutation.toSyncBookmark(logger) ?: return@mapNotNull null
            RemoteModelMutation(
                model = bookmark,
                remoteID = resourceId,
                mutation = mutation.mutation
            )
        }
    }

    private fun toSyncMutation(localMutation: LocalModelMutation<SyncBookmark>): SyncMutation {
        return SyncMutation(
            resource = resourceName,
            resourceId = localMutation.remoteID,
            mutation = localMutation.mutation,
            data = if (localMutation.mutation == Mutation.DELETED) null else localMutation.model.toResourceData(),
            timestamp = null
        )
    }

    private fun preprocessLocalMutations(
        mutations: List<LocalModelMutation<SyncBookmark>>
    ): List<LocalModelMutation<SyncBookmark>> {
        val preprocessor = LocalMutationsPreprocessor()
        return preprocessor.preprocess(mutations)
    }

    private suspend fun preprocessRemoteMutations(
        mutations: List<RemoteModelMutation<SyncBookmark>>
    ): List<RemoteModelMutation<SyncBookmark>> {
        val preprocessor = RemoteMutationsPreprocessor { remoteIds ->
            configurations.localDataFetcher.checkLocalExistence(remoteIds)
        }
        return preprocessor.preprocess(mutations)
    }

    private fun detectConflicts(
        remote: List<RemoteModelMutation<SyncBookmark>>,
        local: List<LocalModelMutation<SyncBookmark>>
    ): ConflictDetectionResult<SyncBookmark> {
        val conflictDetector = ConflictDetector(remote, local)
        return conflictDetector.getConflicts()
    }

    private fun resolveConflicts(
        conflicts: List<ResourceConflict<SyncBookmark>>
    ): ConflictResolutionResult<SyncBookmark> {
        val resolver = ConflictResolver(conflicts)
        return resolver.resolve()
    }

    private inner class BookmarksResourceSyncPlan(
        private val localMutationsToClear: List<LocalModelMutation<SyncBookmark>>,
        private val remoteMutationsToPersist: List<RemoteModelMutation<SyncBookmark>>,
        private val localMutationsToPush: List<LocalModelMutation<SyncBookmark>>
    ) : ResourceSyncPlan {
        override val resourceName: String = this@BookmarksSyncAdapter.resourceName

        override fun mutationsToPush(): List<SyncMutation> {
            return localMutationsToPush.map { toSyncMutation(it) }
        }

        override suspend fun complete(newToken: Long, pushedMutations: List<SyncMutation>) {
            val parsedPushed = parseRemoteMutations(pushedMutations)
            val preprocessedPushed = preprocessRemoteMutations(parsedPushed)
            val finalRemoteMutations = remoteMutationsToPersist + preprocessedPushed
            configurations.resultNotifier.didSucceed(
                newToken,
                finalRemoteMutations,
                localMutationsToClear
            )
        }
    }
}

private fun SyncMutation.toSyncBookmark(logger: Logger): SyncBookmark? {
    val data = data ?: return null
    val id = resourceId ?: return null
    val normalizedType = data.stringOrNull("bookmarkType") ?: data.stringOrNull("type")
    val lastModified = Instant.fromEpochSeconds(timestamp ?: 0)
    return when (normalizedType?.lowercase()) {
        "page" -> {
            val page = data.intOrNull("key")
            if (page == null) {
                logger.w { "Skipping bookmark mutation without page key: resourceId=$resourceId" }
                null
            } else {
                SyncBookmark.PageBookmark(
                    id = id,
                    page = page,
                    lastModified = lastModified
                )
            }
        }
        "ayah" -> {
            val sura = data.intOrNull("key")
            val ayah = data.intOrNull("verseNumber")
            if (sura != null && ayah != null) {
                SyncBookmark.AyahBookmark(
                    id = id,
                    sura = sura,
                    ayah = ayah,
                    lastModified = lastModified
                )
            } else {
                null
            }
        }
        else -> {
            logger.w { "Skipping bookmark mutation with unsupported type=$normalizedType: resourceId=$resourceId" }
            null
        }
    }
}

private fun SyncBookmark.toResourceData(): JsonObject {
    return when (this) {
        is SyncBookmark.PageBookmark ->
            buildJsonObject {
                put("type", "page")
                put("key", page)
                put("mushaf", 1)
            }
        is SyncBookmark.AyahBookmark ->
            buildJsonObject {
                put("type", "ayah")
                put("key", sura)
                put("verseNumber", ayah)
                put("mushaf", 1)
            }
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.intOrNull(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull
