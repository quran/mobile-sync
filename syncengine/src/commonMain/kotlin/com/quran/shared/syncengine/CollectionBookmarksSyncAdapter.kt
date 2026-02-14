package com.quran.shared.syncengine

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.conflict.CollectionBookmarksConflictDetector
import com.quran.shared.syncengine.conflict.CollectionBookmarksConflictResolver
import com.quran.shared.syncengine.conflict.ConflictDetectionResult
import com.quran.shared.syncengine.conflict.ConflictResolutionResult
import com.quran.shared.syncengine.conflict.ResourceConflict
import com.quran.shared.syncengine.model.SyncCollectionBookmark
import com.quran.shared.syncengine.preprocessing.CollectionBookmarksRemoteMutationsPreprocessor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Instant

internal class CollectionBookmarksSyncAdapter(
    private val configurations: CollectionBookmarksSynchronizationConfigurations
) : SyncResourceAdapter {

    override val resourceName: String = "COLLECTION_BOOKMARK"
    override val localModificationDateFetcher: LocalModificationDateFetcher =
        configurations.localModificationDateFetcher

    private val logger = Logger.withTag("CollectionBookmarksSyncAdapter")

    override suspend fun buildPlan(
        lastModificationDate: Long,
        remoteMutations: List<SyncMutation>
    ): ResourceSyncPlan {
        val localMutations = configurations.localDataFetcher.fetchLocalMutations(lastModificationDate)
        logger.i {
            "Local data fetched for $resourceName: " +
                "lastModificationDate=$lastModificationDate, localMutations=${localMutations.size}"
        }

        val parsedRemote = parseRemoteMutations(remoteMutations)
        val preprocessedRemote = preprocessRemoteMutations(parsedRemote)
        logger.d {
            "Remote mutations preprocessed for $resourceName: " +
                "${parsedRemote.size} -> ${preprocessedRemote.size}"
        }

        val conflictDetection = detectConflicts(preprocessedRemote, localMutations)
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

        return CollectionBookmarksResourceSyncPlan(
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
    ): List<RemoteModelMutation<SyncCollectionBookmark>> {
        return mutations.mapNotNull { mutation ->
            if (!mutation.resource.equals(resourceName, ignoreCase = true)) {
                return@mapNotNull null
            }
            val bookmarkId = mutation.data?.stringOrNull("bookmarkId")
            val resourceId = mutation.resourceId ?: bookmarkId
            if (resourceId == null) {
                logger.w { "Skipping collection bookmark mutation without resourceId" }
                return@mapNotNull null
            }
            val collectionBookmark = mutation.toSyncCollectionBookmark(logger, configurations.localDataFetcher) ?: return@mapNotNull null
            RemoteModelMutation(
                model = collectionBookmark,
                remoteID = resourceId,
                mutation = mutation.mutation
            )
        }
    }

    private fun toSyncMutation(localMutation: LocalModelMutation<SyncCollectionBookmark>): SyncMutation {
        return SyncMutation(
            resource = resourceName,
            resourceId = localMutation.remoteID,
            mutation = localMutation.mutation,
            data = localMutation.model.toResourceData(),
            timestamp = null
        )
    }

    private suspend fun preprocessRemoteMutations(
        mutations: List<RemoteModelMutation<SyncCollectionBookmark>>
    ): List<RemoteModelMutation<SyncCollectionBookmark>> {
        val preprocessor = CollectionBookmarksRemoteMutationsPreprocessor { remoteIds ->
            configurations.localDataFetcher.checkLocalExistence(remoteIds)
        }
        return preprocessor.preprocess(mutations)
    }

    private fun detectConflicts(
        remote: List<RemoteModelMutation<SyncCollectionBookmark>>,
        local: List<LocalModelMutation<SyncCollectionBookmark>>
    ): ConflictDetectionResult<SyncCollectionBookmark> {
        val conflictDetector = CollectionBookmarksConflictDetector(remote, local)
        return conflictDetector.getConflicts()
    }

    private fun resolveConflicts(
        conflicts: List<ResourceConflict<SyncCollectionBookmark>>
    ): ConflictResolutionResult<SyncCollectionBookmark> {
        val resolver = CollectionBookmarksConflictResolver(conflicts)
        return resolver.resolve()
    }

    private fun mapPushedMutations(
        localMutations: List<LocalModelMutation<SyncCollectionBookmark>>,
        pushedMutations: List<SyncMutation>
    ): List<RemoteModelMutation<SyncCollectionBookmark>> {
        if (localMutations.size != pushedMutations.size) {
            val message = "Mismatched pushed mutation counts for $resourceName: " +
                "local=${localMutations.size}, remote=${pushedMutations.size}"
            logger.e { message }
            throw IllegalStateException(message)
        }

        return localMutations.mapIndexed { index, localMutation ->
            val pushedMutation = pushedMutations[index]
            if (!pushedMutation.resource.equals(resourceName, ignoreCase = true)) {
                val message = "Unexpected pushed mutation resource=${pushedMutation.resource} for $resourceName"
                logger.e { message }
                throw IllegalStateException(message)
            }
            val remoteId = pushedMutation.resourceId
            if (remoteId == null) {
                val message = "Missing resourceId for pushed mutation at index=$index for $resourceName"
                logger.e { message }
                throw IllegalStateException(message)
            }
            if (pushedMutation.mutation != localMutation.mutation) {
                logger.w {
                    "Mutation type mismatch at index=$index for $resourceName: " +
                        "local=${localMutation.mutation}, remote=${pushedMutation.mutation}"
                }
            }

            RemoteModelMutation(
                model = localMutation.model,
                remoteID = remoteId,
                mutation = pushedMutation.mutation
            )
        }
    }

    private inner class CollectionBookmarksResourceSyncPlan(
        private val localMutationsToClear: List<LocalModelMutation<SyncCollectionBookmark>>,
        private val remoteMutationsToPersist: List<RemoteModelMutation<SyncCollectionBookmark>>,
        private val localMutationsToPush: List<LocalModelMutation<SyncCollectionBookmark>>
    ) : ResourceSyncPlan {
        override val resourceName: String = this@CollectionBookmarksSyncAdapter.resourceName

        override fun mutationsToPush(): List<SyncMutation> {
            return localMutationsToPush.map { toSyncMutation(it) }
        }

        override suspend fun complete(newToken: Long, pushedMutations: List<SyncMutation>) {
            val mappedPushed = mapPushedMutations(localMutationsToPush, pushedMutations)
            val preprocessedPushed = preprocessRemoteMutations(mappedPushed)
            val finalRemoteMutations = remoteMutationsToPersist + preprocessedPushed
            configurations.resultNotifier.didSucceed(
                newToken,
                finalRemoteMutations,
                localMutationsToClear
            )
        }
    }
}

private suspend fun SyncMutation.toSyncCollectionBookmark(
    logger: Logger,
    localDataFetcher: LocalDataFetcher<SyncCollectionBookmark>
): SyncCollectionBookmark? {
    val data = data ?: return null
    val collectionId = data.stringOrNull("collectionId")
    if (collectionId.isNullOrEmpty()) {
        logger.w { "Skipping collection bookmark mutation without collectionId: resourceId=$resourceId" }
        return null
    }
    val normalizedType = data.stringOrNull("bookmarkType") ?: data.stringOrNull("type")
    val lastModified = Instant.fromEpochMilliseconds(timestamp ?: 0)
    val bookmarkId = data.stringOrNull("bookmarkId")
        ?: data.stringOrNull("bookmark_id")
        ?: parseBookmarkId(resourceId, collectionId) ?: return null
    return when (normalizedType?.lowercase()) {
        "page" -> {
            val page = data.intOrNull("key")
            if (page == null) {
                logger.w { "Skipping collection bookmark mutation without page key: resourceId=$resourceId" }
                null
            } else {
                SyncCollectionBookmark.PageBookmark(
                    collectionId = collectionId,
                    page = page,
                    lastModified = lastModified,
                    bookmarkId = bookmarkId
                )
            }
        }
        "ayah" -> {
            val sura = data.intOrNull("key")
            val ayah = data.intOrNull("verseNumber")
            if (sura != null && ayah != null) {
                SyncCollectionBookmark.AyahBookmark(
                    collectionId = collectionId,
                    sura = sura,
                    ayah = ayah,
                    lastModified = lastModified,
                    bookmarkId = bookmarkId
                )
            } else {
                null
            }
        }
        else -> {
            val localModel = localDataFetcher.fetchLocalModel(bookmarkId)
            if(localModel != null) {
                logger.d { "Mapped unknown collection bookmark type using local data: resourceId=$localModel" }
                when (localModel) {
                    is SyncCollectionBookmark.PageBookmark ->
                        SyncCollectionBookmark.PageBookmark(
                            collectionId = collectionId,
                            page = localModel.page,
                            lastModified = lastModified,
                            bookmarkId = bookmarkId
                        )
                    is SyncCollectionBookmark.AyahBookmark -> SyncCollectionBookmark.AyahBookmark(
                        collectionId = collectionId,
                        sura = localModel.sura,
                        ayah = localModel.ayah,
                        lastModified = lastModified,
                        bookmarkId = bookmarkId,
                    )
                }
            } else {
                logger.w { "Skipping collection bookmark mutation with unsupported type=$normalizedType: resourceId=$resourceId" }
                null
            }
        }
    }
}

private fun SyncCollectionBookmark.toResourceData(): JsonObject {
    return when (this) {
        is SyncCollectionBookmark.PageBookmark ->
            buildJsonObject {
                put("collectionId", collectionId)
                put("type", "page")
                put("key", page)
                put("mushaf", 1)
                bookmarkId?.let { put("bookmarkId", it) }
            }
        is SyncCollectionBookmark.AyahBookmark ->
            buildJsonObject {
                put("collectionId", collectionId)
                put("type", "ayah")
                put("key", sura)
                put("verseNumber", ayah)
                put("mushaf", 1)
                bookmarkId?.let { put("bookmarkId", it) }
            }
    }
}

private fun parseBookmarkId(resourceId: String?, collectionId: String): String? {
    if (resourceId.isNullOrEmpty()) {
        return null
    }
    val prefix = "$collectionId-"
    return if (resourceId.startsWith(prefix)) {
        resourceId.removePrefix(prefix)
    } else {
        null
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.intOrNull(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull
