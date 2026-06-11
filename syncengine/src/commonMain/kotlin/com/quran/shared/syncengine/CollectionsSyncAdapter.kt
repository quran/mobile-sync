package com.quran.shared.syncengine

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.conflict.CollectionsConflictDetector
import com.quran.shared.syncengine.conflict.CollectionsConflictResolver
import com.quran.shared.syncengine.conflict.ConflictDetectionResult
import com.quran.shared.syncengine.conflict.ConflictResolutionResult
import com.quran.shared.syncengine.conflict.ResourceConflict
import com.quran.shared.syncengine.model.SyncCollection
import com.quran.shared.syncengine.preprocessing.CollectionsLocalMutationsPreprocessor
import com.quran.shared.syncengine.preprocessing.CollectionsRemoteMutationsPreprocessor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

internal class CollectionsSyncAdapter(
    private val configurations: CollectionsSynchronizationConfigurations
) : SyncResourceAdapter {

    override val resourceName: String = "COLLECTION"
    override val localModificationDateFetcher: LocalModificationDateFetcher =
        configurations.localModificationDateFetcher

    private val logger = Logger.withTag("CollectionsSyncAdapter")

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

        return CollectionsResourceSyncPlan(
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
    ): List<RemoteModelMutation<SyncCollection>> {
        return mutations.mapSimpleRemoteModelMutations(resourceName) { it.toSyncCollection(logger) }
    }

    private fun preprocessLocalMutations(
        mutations: List<LocalModelMutation<SyncCollection>>
    ): List<LocalModelMutation<SyncCollection>> {
        val preprocessor = CollectionsLocalMutationsPreprocessor()
        return preprocessor.preprocess(mutations)
    }

    private suspend fun preprocessRemoteMutations(
        mutations: List<RemoteModelMutation<SyncCollection>>
    ): List<RemoteModelMutation<SyncCollection>> {
        val preprocessor = CollectionsRemoteMutationsPreprocessor { remoteIds ->
            configurations.localDataFetcher.checkLocalExistence(remoteIds)
        }
        return preprocessor.preprocess(mutations)
    }

    private fun detectConflicts(
        remote: List<RemoteModelMutation<SyncCollection>>,
        local: List<LocalModelMutation<SyncCollection>>
    ): ConflictDetectionResult<SyncCollection> {
        val conflictDetector = CollectionsConflictDetector(remote, local)
        return conflictDetector.getConflicts()
    }

    private fun resolveConflicts(
        conflicts: List<ResourceConflict<SyncCollection>>
    ): ConflictResolutionResult<SyncCollection> {
        val resolver = CollectionsConflictResolver(conflicts)
        return resolver.resolve()
    }

    private inner class CollectionsResourceSyncPlan(
        private val localMutationsToClear: List<LocalModelMutation<SyncCollection>>,
        private val remoteMutationsToPersist: List<RemoteModelMutation<SyncCollection>>,
        private val localMutationsToPush: List<LocalModelMutation<SyncCollection>>
    ) : ResourceSyncPlan {
        override val resourceName: String = this@CollectionsSyncAdapter.resourceName

        override suspend fun mutationsToPush(): List<SyncMutation> =
            localMutationsToPush.map {
                it.toSyncMutation(
                    resourceName = resourceName,
                    resourceData = SyncCollection::toResourceData,
                    timestamp = { model -> model.lastModified.toEpochMilliseconds() }
                )
            }

        override suspend fun complete(newToken: Long, pushedMutations: List<SyncMutation>) {
            val mappedPushed = mapPushedModelMutations(resourceName, localMutationsToPush, pushedMutations)
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

private fun SyncMutation.toSyncCollection(logger: Logger): SyncCollection? {
    val id = resourceId ?: return null
    val lastModified = Instant.fromEpochMilliseconds(timestamp ?: 0)
    return when (mutation) {
        Mutation.DELETED -> SyncCollection(
            id = id,
            name = null,
            lastModified = lastModified
        )
        Mutation.CREATED, Mutation.MODIFIED -> {
            val data = data ?: return null
            val name = data.stringOrNull("name")
            if (name.isNullOrEmpty()) {
                logger.w { "Skipping collection mutation without name: resourceId=$resourceId" }
                null
            } else {
                SyncCollection(
                    id = id,
                    name = name,
                    lastModified = lastModified
                )
            }
        }
    }
}

private fun SyncCollection.toResourceData(): JsonObject {
    val collectionName = requireNotNull(name) { "Collection name is required for resource data." }
    return buildJsonObject {
        put("name", collectionName)
    }
}
