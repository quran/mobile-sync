package com.quran.shared.syncengine

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.conflict.ConflictDetector
import com.quran.shared.syncengine.conflict.ConflictResolutionResult
import com.quran.shared.syncengine.conflict.ConflictResolver
import com.quran.shared.syncengine.conflict.ConflictDetectionResult
import com.quran.shared.syncengine.conflict.ResourceConflict
import com.quran.shared.syncengine.model.SyncBookmark
import com.quran.shared.syncengine.model.conflictKey
import com.quran.shared.syncengine.preprocessing.BookmarksLocalMutationsPreprocessor
import com.quran.shared.syncengine.preprocessing.BookmarksRemoteMutationsPreprocessor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

    private suspend fun parseRemoteMutations(
        mutations: List<SyncMutation>
    ): List<RemoteModelMutation<SyncBookmark>> {
        return mutations.mapNotNull { mutation ->
            if (!mutation.resource.equals(resourceName, ignoreCase = true)) {
                return@mapNotNull null
            }
            val resourceId = mutation.requireSimpleResourceRemoteId(resourceName)
            val bookmark = mutation.toSyncBookmark(logger, configurations.localDataFetcher) ?: return@mapNotNull null
            RemoteModelMutation(
                model = bookmark,
                remoteID = resourceId,
                mutation = mutation.mutation
            )
        }
    }

    private fun preprocessLocalMutations(
        mutations: List<LocalModelMutation<SyncBookmark>>
    ): List<LocalModelMutation<SyncBookmark>> {
        val preprocessor = BookmarksLocalMutationsPreprocessor()
        return preprocessor.preprocess(mutations)
    }

    private suspend fun preprocessRemoteMutations(
        mutations: List<RemoteModelMutation<SyncBookmark>>
    ): List<RemoteModelMutation<SyncBookmark>> {
        val preprocessor = BookmarksRemoteMutationsPreprocessor { remoteIds ->
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
        override suspend fun mutationsToPush(): List<SyncMutation> =
            localMutationsToPush.map {
                it.toSyncMutation(
                    resourceName = resourceName,
                    resourceData = SyncBookmark::toResourceData,
                    timestamp = { model -> model.lastModified.toEpochMilliseconds() },
                    createdTimestamp = { model -> model.createdAt?.toEpochMilliseconds() }
                )
            }

        override suspend fun complete(newToken: Long, pushedMutations: List<SyncMutation>) {
            val mappedPushed = mapPushedModelMutations(
                resourceName,
                localMutationsToPush,
                pushedMutations
            )
            val preprocessedPushed = preprocessRemoteMutations(mappedPushed)
            val finalRemoteMutations = preprocessedPushed + remoteMutationsToPersist
            val localMutationsMappedFromReplay = localMutationsToClear
                .mapReplayCreatedClears(remoteMutationsToPersist) { it.conflictKey() }
            configurations.resultNotifier.didSucceed(
                newToken,
                finalRemoteMutations,
                localMutationsMappedFromReplay
            )
        }
    }
}

private suspend fun SyncMutation.toSyncBookmark(
    logger: Logger,
    localDataFetcher: LocalDataFetcher<SyncBookmark>
): SyncBookmark? {
    val id = resourceId ?: return null
    val normalizedType = data?.stringOrNull("bookmarkType") ?: data?.stringOrNull("type")
    val lastModified = clientUpdatedAtInstant()
    val createdAt = clientCreatedAtInstant()
    return when (normalizedType?.lowercase()) {
        "ayah" -> {
            val sura = data?.intOrNull("key")
            val ayah = data?.intOrNull("verseNumber")
            if (sura != null && ayah != null) {
                SyncBookmark.AyahBookmark(
                    id = id,
                    sura = sura,
                    ayah = ayah,
                    lastModified = lastModified,
                    createdAt = createdAt
                )
            } else {
                null
            }
        }
        "page", "surah", "juz" -> {
            logger.w { "Skipping unsupported non-ayah bookmark type=$normalizedType: resourceId=$resourceId" }
            null
        }
        else -> {
            val localModel = localDataFetcher.fetchLocalModel(id)
            if (localModel != null) {
                logger.d { "Mapped unknown bookmark type using local data: resourceId=$id" }
                when (localModel) {
                    is SyncBookmark.AyahBookmark -> localModel.copy(
                        lastModified = lastModified,
                        createdAt = createdAt ?: localModel.createdAt
                    )
                }
            } else {
                logger.w { "Skipping bookmark mutation with unsupported type=$normalizedType: resourceId=$resourceId" }
                null
            }
        }
    }
}

private fun SyncBookmark.toResourceData(): JsonObject {
    return when (this) {
        is SyncBookmark.AyahBookmark -> buildJsonObject {
            put("type", "ayah")
            put("key", sura)
            put("verseNumber", ayah)
            put("mushaf", 1)
        }
    }
}
