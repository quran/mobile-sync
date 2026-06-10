package com.quran.shared.syncengine

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LOCAL_MUTATION_BOOKMARK_DEFAULT_FACET
import com.quran.shared.mutations.LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.LocalMutationAck
import com.quran.shared.mutations.LocalMutationResource
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.conflict.CollectionBookmarksConflictDetector
import com.quran.shared.syncengine.conflict.CollectionBookmarksConflictResolver
import com.quran.shared.syncengine.conflict.ConflictDetectionResult
import com.quran.shared.syncengine.conflict.ConflictResolutionResult
import com.quran.shared.syncengine.conflict.ResourceConflict
import com.quran.shared.syncengine.model.SyncCollectionBookmark
import com.quran.shared.syncengine.model.collectionBookmarkRemoteId
import com.quran.shared.syncengine.model.conflictKey
import com.quran.shared.syncengine.model.remoteIdOrNull
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
) : SyncResourceAdapter, PreDependencyDeletionSyncResourceAdapter {

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
        val preprocessedRemote = preprocessRemoteMutations(parsedRemote, localMutations)
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

    override suspend fun buildPreDependencyDeletionPlan(
        lastModificationDate: Long,
        remoteMutations: List<SyncMutation>
    ): ResourceSyncPlan? {
        val localMutations = configurations.localDataFetcher.fetchLocalMutations(lastModificationDate)
            .filter { mutation -> mutation.mutation == Mutation.DELETED }
        if (localMutations.isEmpty()) {
            return null
        }

        logger.i {
            "Local deletion mutations fetched for pre-dependency $resourceName phase: " +
                "lastModificationDate=$lastModificationDate, localMutations=${localMutations.size}"
        }

        val parsedRemote = parseRemoteMutations(remoteMutations)
        val preprocessedRemote = preprocessRemoteMutations(parsedRemote, localMutations)
        val conflictDetection = detectConflicts(preprocessedRemote, localMutations)
        val conflictResolution = resolveConflicts(conflictDetection.conflicts)
        val localMutationsToPush = conflictDetection.nonConflictingLocalMutations + conflictResolution.mutationsToPush
        if (localMutationsToPush.isEmpty()) {
            logger.d {
                "No non-conflicting deletion mutations for pre-dependency $resourceName phase: " +
                    "conflicts=${conflictDetection.conflicts.size}"
            }
            return null
        }

        return CollectionBookmarksResourceSyncPlan(
            localMutationsToClear = localMutationsToPush,
            remoteMutationsToPersist = emptyList(),
            localMutationsToPush = localMutationsToPush
        )
    }

    override suspend fun didFail(message: String) {
        configurations.resultNotifier.didFail(message)
    }

    private suspend fun parseRemoteMutations(
        mutations: List<SyncMutation>
    ): List<RemoteModelMutation<SyncCollectionBookmark>> {
        val remoteAyahBookmarksById = mutations
            .asSequence()
            .mapNotNull { it.toRemoteAyahBookmarkLookup() }
            .associateBy { it.id }

        return mutations.mapNotNull { mutation ->
            if (!mutation.resource.equals(resourceName, ignoreCase = true)) {
                return@mapNotNull null
            }
            val collectionId = mutation.data?.stringOrNull("collectionId")
            val bookmarkId = mutation.data?.stringOrNull("bookmarkId")
                ?: mutation.data?.stringOrNull("bookmark_id")
            val resourceId = mutation.resourceId ?: if (!collectionId.isNullOrEmpty() && !bookmarkId.isNullOrEmpty()) {
                collectionBookmarkRemoteId(collectionId, bookmarkId)
            } else {
                null
            }
            if (resourceId == null) {
                logger.w { "Skipping collection bookmark mutation without resourceId" }
                return@mapNotNull null
            }
            val collectionBookmark = mutation.toSyncCollectionBookmark(
                logger = logger,
                localDataFetcher = configurations.localDataFetcher,
                remoteAyahBookmarksById = remoteAyahBookmarksById
            ) ?: return@mapNotNull null
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
            timestamp = localMutation.model.lastModified.toEpochMilliseconds()
        )
    }

    private suspend fun preprocessRemoteMutations(
        mutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
        localMutations: List<LocalModelMutation<SyncCollectionBookmark>> = emptyList()
    ): List<RemoteModelMutation<SyncCollectionBookmark>> {
        val preprocessor = CollectionBookmarksRemoteMutationsPreprocessor { remoteIds ->
            configurations.localDataFetcher.checkLocalExistence(remoteIds)
        }
        return preprocessor.preprocess(mutations, localMutations)
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
        validatePushedMutationCount(resourceName, localMutations.size, pushedMutations.size)

        return localMutations.mapIndexed { index, localMutation ->
            val pushedMutation = pushedMutations[index]
            val requestMutation = toSyncMutation(localMutation)
            val remoteId = validatePushedMutationAck(
                resourceName = resourceName,
                index = index,
                expectedRemoteId = localMutation.remoteID,
                expectedMutation = localMutation.mutation,
                pushedMutation = pushedMutation,
                acknowledgedRemoteId = pushedMutation.acknowledgedRemoteIdFor(requestMutation)
                    ?: localMutation.model.remoteIdOrNull()
            )
            val model = localMutation.model.withPushedBookmarkId(
                pushedMutation = pushedMutation,
                isValidatedDefaultCreateAck = localMutation.isDefaultCollectionBookmarkCreate()
            )

            RemoteModelMutation(
                model = model,
                remoteID = remoteId,
                mutation = pushedMutation.mutation,
                ack = localMutation.ack
            )
        }
    }

    private inner class CollectionBookmarksResourceSyncPlan(
        private val localMutationsToClear: List<LocalModelMutation<SyncCollectionBookmark>>,
        private val remoteMutationsToPersist: List<RemoteModelMutation<SyncCollectionBookmark>>,
        private val localMutationsToPush: List<LocalModelMutation<SyncCollectionBookmark>>
    ) : ResourceSyncPlan {
        override val resourceName: String = this@CollectionBookmarksSyncAdapter.resourceName
        private var localMutationsToPushForCompletion = localMutationsToPush
        private var localMutationsToClearForCompletion = localMutationsToClear
        private var markedInFlightAcks: List<LocalMutationAck> = emptyList()

        override suspend fun mutationsToPush(): List<SyncMutation> =
            localMutationsToPushForCompletion.map { toSyncMutation(it) }

        override suspend fun markMutationsInFlight() {
            markedInFlightAcks = configurations.localDataFetcher.markLocalMutationsInFlight(localMutationsToPush)
            val markedAckKeys = markedInFlightAcks
                .filter { ack -> ack.observedPendingOp == Mutation.CREATED }
                .map { it.markerKey() }
                .toSet()
            val pushedCreateAckKeys = localMutationsToPush
                .mapNotNull { it.collectionBookmarkCreateMarkerKey() }
                .toSet()
            localMutationsToPushForCompletion = localMutationsToPush
                .filterPushableCreates(markedAckKeys)
                .map { it.withIncrementedAckIfMarked(markedAckKeys) }
            localMutationsToClearForCompletion = localMutationsToClear
                .filterClearableCreates(markedAckKeys, pushedCreateAckKeys)
                .map { it.withIncrementedAckIfMarked(markedAckKeys) }
        }

        override suspend fun rollbackMutationsInFlight() {
            configurations.localDataFetcher.rollbackLocalMutationsInFlight(markedInFlightAcks)
            markedInFlightAcks = emptyList()
            localMutationsToPushForCompletion = localMutationsToPush
            localMutationsToClearForCompletion = localMutationsToClear
        }

        override suspend fun complete(newToken: Long, pushedMutations: List<SyncMutation>) {
            val mappedPushed = mapPushedMutations(localMutationsToPushForCompletion, pushedMutations)
            val pushedLocalMutationsToClear = mapPushedLocalMutations(localMutationsToPushForCompletion, mappedPushed)
            val pushedLocalMutationsById = pushedLocalMutationsToClear.associateBy { it.localID }
            val finalLocalMutationsToClear = localMutationsToClearForCompletion
                .mapReplayCreatedClears(remoteMutationsToPersist)
                .map { localMutation ->
                    pushedLocalMutationsById[localMutation.localID] ?: localMutation
                }
            configurations.resultNotifier.didSucceed(
                newToken,
                remoteMutationsToPersist,
                finalLocalMutationsToClear
            )
        }
    }
}

private fun LocalMutationAck.markerKey(): String =
    "$localID|$resource|$facet|$observedPendingOp|$observedPendingVersion"

private fun LocalMutationAck.isCollectionBookmarkCreate(): Boolean =
    resource == LocalMutationResource.COLLECTION_BOOKMARK &&
        facet in COLLECTION_BOOKMARK_CREATE_MARKER_FACETS &&
        observedPendingOp == Mutation.CREATED

private fun LocalMutationAck.isDefaultCollectionBookmarkCreate(): Boolean =
    resource == LocalMutationResource.COLLECTION_BOOKMARK &&
        facet == LOCAL_MUTATION_BOOKMARK_DEFAULT_FACET &&
        observedPendingOp == Mutation.CREATED

private val COLLECTION_BOOKMARK_CREATE_MARKER_FACETS = setOf(
    LOCAL_MUTATION_BOOKMARK_DEFAULT_FACET,
    LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET
)

private fun LocalModelMutation<SyncCollectionBookmark>.collectionBookmarkCreateMarkerKey(): String? {
    val ack = ack ?: return null
    return if (ack.isCollectionBookmarkCreate()) ack.markerKey() else null
}

private fun LocalModelMutation<SyncCollectionBookmark>.isDefaultCollectionBookmarkCreate(): Boolean =
    mutation == Mutation.CREATED && ack?.isDefaultCollectionBookmarkCreate() == true

private fun List<LocalModelMutation<SyncCollectionBookmark>>.filterPushableCreates(
    markedAckKeys: Set<String>
): List<LocalModelMutation<SyncCollectionBookmark>> =
    filter { mutation ->
        val createKey = mutation.collectionBookmarkCreateMarkerKey()
        createKey == null || createKey in markedAckKeys
    }

private fun List<LocalModelMutation<SyncCollectionBookmark>>.filterClearableCreates(
    markedAckKeys: Set<String>,
    pushedCreateAckKeys: Set<String>
): List<LocalModelMutation<SyncCollectionBookmark>> =
    filter { mutation ->
        val createKey = mutation.collectionBookmarkCreateMarkerKey()
        createKey == null || createKey !in pushedCreateAckKeys || createKey in markedAckKeys
    }

private fun LocalModelMutation<SyncCollectionBookmark>.withIncrementedAckIfMarked(
    markedAckKeys: Set<String>
): LocalModelMutation<SyncCollectionBookmark> {
    val ack = ack ?: return this
    if (ack.markerKey() !in markedAckKeys) {
        return this
    }
    return LocalModelMutation(
        model = model,
        remoteID = remoteID,
        localID = localID,
        mutation = mutation,
        ack = ack.copy(observedPendingVersion = ack.observedPendingVersion + 1)
    )
}

private fun mapPushedLocalMutations(
    localMutations: List<LocalModelMutation<SyncCollectionBookmark>>,
    pushedMutations: List<RemoteModelMutation<SyncCollectionBookmark>>
): List<LocalModelMutation<SyncCollectionBookmark>> {
    return localMutations.zip(pushedMutations) { local, pushed ->
        LocalModelMutation(
            model = pushed.model,
            remoteID = pushed.remoteID,
            localID = local.localID,
            mutation = pushed.mutation,
            ack = local.ack
        )
    }
}

private fun List<LocalModelMutation<SyncCollectionBookmark>>.mapReplayCreatedClears(
    remoteMutationsToPersist: List<RemoteModelMutation<SyncCollectionBookmark>>
): List<LocalModelMutation<SyncCollectionBookmark>> =
    map { local ->
        val replayedRemote = remoteMutationsToPersist.singleOrNull { remote ->
            local.mutation == Mutation.CREATED &&
                remote.mutation == Mutation.CREATED &&
                local.model.conflictKey() == remote.model.conflictKey()
        } ?: return@map local
        LocalModelMutation(
            model = replayedRemote.model,
            remoteID = replayedRemote.remoteID,
            localID = local.localID,
            mutation = replayedRemote.mutation,
            ack = local.ack
        )
    }

private suspend fun SyncMutation.toSyncCollectionBookmark(
    logger: Logger,
    localDataFetcher: LocalDataFetcher<SyncCollectionBookmark>,
    remoteAyahBookmarksById: Map<String, RemoteAyahBookmarkLookup>
): SyncCollectionBookmark? {
    val lastModified = Instant.fromEpochMilliseconds(timestamp ?: 0)
    val data = data
    if (data == null) {
        if (mutation == Mutation.DELETED && !resourceId.isNullOrEmpty()) {
            val localModel = localDataFetcher.fetchLocalModel(resourceId)
            if (localModel != null) {
                logger.d { "Mapped collection bookmark delete using local relation data: resourceId=$resourceId" }
                return when (localModel) {
                    is SyncCollectionBookmark.AyahBookmark -> localModel.copy(lastModified = lastModified)
                }
            }
            logger.w { "Skipping collection bookmark delete without data or local model: resourceId=$resourceId" }
        }
        return null
    }
    val collectionId = data.stringOrNull("collectionId")
    if (collectionId.isNullOrEmpty()) {
        logger.w { "Skipping collection bookmark mutation without collectionId: resourceId=$resourceId" }
        return null
    }
    val normalizedType = data.stringOrNull("bookmarkType") ?: data.stringOrNull("type")
    val bookmarkId = data.stringOrNull("bookmarkId")
        ?: data.stringOrNull("bookmark_id")
        ?: parseBookmarkId(resourceId, collectionId)
    if (bookmarkId.isNullOrEmpty()) {
        logger.w { "Skipping collection bookmark mutation without bookmarkId: resourceId=$resourceId" }
        return null
    }
    return when (normalizedType?.lowercase()) {
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
            if (localModel != null) {
                logger.d { "Mapped unknown collection bookmark type using local data: bookmarkId=$bookmarkId" }
                when (localModel) {
                    is SyncCollectionBookmark.AyahBookmark -> SyncCollectionBookmark.AyahBookmark(
                        collectionId = collectionId,
                        sura = localModel.sura,
                        ayah = localModel.ayah,
                        lastModified = lastModified,
                        bookmarkId = bookmarkId
                    )
                }
            } else {
                val remoteBookmark = remoteAyahBookmarksById[bookmarkId]
                if (remoteBookmark != null) {
                    logger.d {
                        "Mapped collection bookmark using same-batch remote bookmark payload: bookmarkId=$bookmarkId"
                    }
                    SyncCollectionBookmark.AyahBookmark(
                        collectionId = collectionId,
                        sura = remoteBookmark.sura,
                        ayah = remoteBookmark.ayah,
                        lastModified = lastModified,
                        bookmarkId = bookmarkId
                    )
                } else {
                    logger.w {
                        "Skipping collection bookmark mutation with unsupported type=$normalizedType: resourceId=$resourceId"
                    }
                    null
                }
            }
        }
    }
}

private fun SyncCollectionBookmark.toResourceData(): JsonObject {
    return when (this) {
        is SyncCollectionBookmark.AyahBookmark -> buildJsonObject {
            put("collectionId", collectionId)
            bookmarkId?.let { put("bookmarkId", it) }
            put("type", "ayah")
            put("key", sura)
            put("verseNumber", ayah)
            put("mushaf", 1)
        }
    }
}

private fun SyncCollectionBookmark.withPushedBookmarkId(
    pushedMutation: SyncMutation,
    isValidatedDefaultCreateAck: Boolean
): SyncCollectionBookmark {
    return when (this) {
        is SyncCollectionBookmark.AyahBookmark -> {
            val pushedBookmarkId = if (isValidatedDefaultCreateAck) {
                pushedMutation.pushedBookmarkIdFor(this)
                    ?: pushedMutation.pushedBookmarkIdPayloadEvidence()
                    ?: bookmarkId
            } else {
                bookmarkId
            }
            copy(bookmarkId = pushedBookmarkId)
        }
    }
}

private fun SyncMutation.pushedBookmarkIdPayloadEvidence(): String? =
    data?.stringOrNull("bookmarkId")
        ?: data?.stringOrNull("bookmark_id")

private fun SyncMutation.pushedBookmarkIdFor(
    localModel: SyncCollectionBookmark.AyahBookmark
): String? {
    val data = data ?: return null
    val bookmarkId = data.stringOrNull("bookmarkId")
        ?: data.stringOrNull("bookmark_id")
        ?: return null
    val sura = data.intOrNull("key")
    val ayah = data.intOrNull("verseNumber")
    return if (sura == localModel.sura && ayah == localModel.ayah) {
        bookmarkId
    } else {
        null
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

private data class RemoteAyahBookmarkLookup(
    val id: String,
    val sura: Int,
    val ayah: Int
)

private fun SyncMutation.toRemoteAyahBookmarkLookup(): RemoteAyahBookmarkLookup? {
    if (!resource.equals("BOOKMARK", ignoreCase = true)) {
        return null
    }
    val id = resourceId ?: return null
    val bookmarkType = data?.stringOrNull("bookmarkType") ?: data?.stringOrNull("type")
    if (!bookmarkType.equals("ayah", ignoreCase = true)) {
        return null
    }
    val sura = data?.intOrNull("key") ?: return null
    val ayah = data.intOrNull("verseNumber") ?: return null
    return RemoteAyahBookmarkLookup(id = id, sura = sura, ayah = ayah)
}
