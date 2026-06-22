package com.quran.shared.syncengine

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LOCAL_MUTATION_BOOKMARK_READING_FACET
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.LocalMutationAck
import com.quran.shared.mutations.LocalMutationResource
import com.quran.shared.mutations.Mutation
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
        private var localMutationsToPushForCompletion = localMutationsToPush
        private var localMutationsToClearForCompletion = localMutationsToClear
        private var markedInFlightAcks: List<LocalMutationAck> = emptyList()

        override suspend fun mutationsToPush(): List<SyncMutation> =
            localMutationsToPushForCompletion.map {
                it.toSyncMutation(
                    resourceName = resourceName,
                    resourceData = SyncBookmark::toResourceData,
                    timestamp = { model -> model.lastModified.toEpochMilliseconds() },
                    createdTimestamp = { model -> model.createdAt?.toEpochMilliseconds() }
                )
            }

        override suspend fun markMutationsInFlight() {
            markedInFlightAcks = configurations.localDataFetcher.markLocalMutationsInFlight(localMutationsToPush)
            val markedAckKeys = markedInFlightAcks
                .filter(LocalMutationAck::isBookmarkReadingCreate)
                .map { it.markerKey() }
                .toSet()
            val pushedCreateAckKeys = localMutationsToPush
                .mapNotNull { it.bookmarkReadingCreateMarkerKey() }
                .toSet()
            localMutationsToPushForCompletion = localMutationsToPush
                .filterPushableCreates(markedAckKeys) { it.bookmarkReadingCreateMarkerKey() }
                .map { it.withIncrementedAckIfMarked(markedAckKeys) }
            localMutationsToClearForCompletion = localMutationsToClear
                .filterClearableCreates(markedAckKeys, pushedCreateAckKeys) { it.bookmarkReadingCreateMarkerKey() }
                .map { it.withIncrementedAckIfMarked(markedAckKeys) }
        }

        override suspend fun rollbackMutationsInFlight() {
            configurations.localDataFetcher.rollbackLocalMutationsInFlight(markedInFlightAcks)
            markedInFlightAcks = emptyList()
            localMutationsToPushForCompletion = localMutationsToPush
            localMutationsToClearForCompletion = localMutationsToClear
        }

        override suspend fun complete(newToken: Long, pushedMutations: List<SyncMutation>) {
            val mappedPushed = mapPushedModelMutations(
                resourceName,
                localMutationsToPushForCompletion,
                pushedMutations
            )
            val preprocessedPushed = preprocessRemoteMutations(mappedPushed)
            val finalRemoteMutations = preprocessedPushed + remoteMutationsToPersist
            val localMutationsMappedFromReplay = localMutationsToClearForCompletion
                .mapReplayCreatedClears(remoteMutationsToPersist) { it.conflictKey() }
            configurations.resultNotifier.didSucceed(
                newToken,
                finalRemoteMutations,
                localMutationsMappedFromReplay
            )
        }
    }
}

private fun LocalMutationAck.isBookmarkReadingCreate(): Boolean =
    resource == LocalMutationResource.BOOKMARK &&
        facet == LOCAL_MUTATION_BOOKMARK_READING_FACET &&
        observedPendingOp == Mutation.CREATED

private fun LocalModelMutation<SyncBookmark>.bookmarkReadingCreateMarkerKey(): String? {
    val ack = ack ?: return null
    return if (ack.isBookmarkReadingCreate()) ack.markerKey() else null
}

private suspend fun SyncMutation.toSyncBookmark(
    logger: Logger,
    localDataFetcher: LocalDataFetcher<SyncBookmark>
): SyncBookmark? {
    val id = resourceId ?: return null
    val normalizedType = data?.stringOrNull("bookmarkType") ?: data?.stringOrNull("type")
    val lastModified = Instant.fromEpochMilliseconds(timestamp ?: 0)
    return when (normalizedType?.lowercase()) {
        "ayah" -> {
            val sura = data?.intOrNull("key")
            val ayah = data?.intOrNull("verseNumber")
            if (sura != null && ayah != null) {
                SyncBookmark.AyahBookmark(
                    id = id,
                    sura = sura,
                    ayah = ayah,
                    isReading = data.booleanOrNull("isReading") ?: false,
                    lastModified = lastModified
                )
            } else {
                null
            }
        }
        "page" -> {
            val page = data?.intOrNull("key")
            if (page != null) {
                SyncBookmark.PageBookmark(
                    id = id,
                    page = page,
                    isReading = data.booleanOrNull("isReading") ?: false,
                    lastModified = lastModified
                )
            } else {
                null
            }
        }
        else -> {
            val localModel = localDataFetcher.fetchLocalModel(id)
            if (localModel != null) {
                logger.d { "Mapped unknown bookmark type using local data: resourceId=$id" }
                when (localModel) {
                    is SyncBookmark.AyahBookmark -> localModel.copy(lastModified = lastModified)
                    is SyncBookmark.PageBookmark -> localModel.copy(lastModified = lastModified)
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
            put("isReading", isReading)
            put("mushaf", 1)
        }
        is SyncBookmark.PageBookmark -> buildJsonObject {
            put("type", "page")
            put("key", page)
            put("isReading", isReading)
            put("mushaf", 1)
        }
    }
}
