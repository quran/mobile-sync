package com.quran.shared.syncengine

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.conflict.ConflictDetectionResult
import com.quran.shared.syncengine.conflict.ConflictResolutionResult
import com.quran.shared.syncengine.conflict.NotesConflictDetector
import com.quran.shared.syncengine.conflict.NotesConflictResolver
import com.quran.shared.syncengine.conflict.ResourceConflict
import com.quran.shared.syncengine.model.SyncNote
import com.quran.shared.syncengine.model.parseNoteRange
import com.quran.shared.syncengine.model.toRangeString
import com.quran.shared.syncengine.preprocessing.NotesLocalMutationsPreprocessor
import com.quran.shared.syncengine.preprocessing.NotesRemoteMutationsPreprocessor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Instant

internal class NotesSyncAdapter(
    private val configurations: NotesSynchronizationConfigurations
) : SyncResourceAdapter {

    override val resourceName: String = "NOTE"
    override val localModificationDateFetcher: LocalModificationDateFetcher =
        configurations.localModificationDateFetcher

    private val logger = Logger.withTag("NotesSyncAdapter")

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

        return NotesResourceSyncPlan(
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
    ): List<RemoteModelMutation<SyncNote>> {
        return mutations.mapNotNull { mutation ->
            if (!mutation.resource.equals(resourceName, ignoreCase = true)) {
                return@mapNotNull null
            }
            val resourceId = mutation.resourceId
            if (resourceId == null) {
                logger.w { "Skipping note mutation without resourceId" }
                return@mapNotNull null
            }
            val note = mutation.toSyncNote(logger) ?: return@mapNotNull null
            RemoteModelMutation(
                model = note,
                remoteID = resourceId,
                mutation = mutation.mutation
            )
        }
    }

    private fun toSyncMutation(localMutation: LocalModelMutation<SyncNote>): SyncMutation {
        return SyncMutation(
            resource = resourceName,
            resourceId = localMutation.remoteID,
            mutation = localMutation.mutation,
            data = if (localMutation.mutation == Mutation.DELETED) null else localMutation.model.toResourceData(),
            timestamp = null
        )
    }

    private fun preprocessLocalMutations(
        mutations: List<LocalModelMutation<SyncNote>>
    ): List<LocalModelMutation<SyncNote>> {
        val preprocessor = NotesLocalMutationsPreprocessor()
        return preprocessor.preprocess(mutations)
    }

    private suspend fun preprocessRemoteMutations(
        mutations: List<RemoteModelMutation<SyncNote>>
    ): List<RemoteModelMutation<SyncNote>> {
        val preprocessor = NotesRemoteMutationsPreprocessor { remoteIds ->
            configurations.localDataFetcher.checkLocalExistence(remoteIds)
        }
        return preprocessor.preprocess(mutations)
    }

    private fun detectConflicts(
        remote: List<RemoteModelMutation<SyncNote>>,
        local: List<LocalModelMutation<SyncNote>>
    ): ConflictDetectionResult<SyncNote> {
        val conflictDetector = NotesConflictDetector(remote, local)
        return conflictDetector.getConflicts()
    }

    private fun resolveConflicts(
        conflicts: List<ResourceConflict<SyncNote>>
    ): ConflictResolutionResult<SyncNote> {
        val resolver = NotesConflictResolver(conflicts)
        return resolver.resolve()
    }

    private fun mapPushedMutations(
        localMutations: List<LocalModelMutation<SyncNote>>,
        pushedMutations: List<SyncMutation>
    ): List<RemoteModelMutation<SyncNote>> {
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

    private inner class NotesResourceSyncPlan(
        private val localMutationsToClear: List<LocalModelMutation<SyncNote>>,
        private val remoteMutationsToPersist: List<RemoteModelMutation<SyncNote>>,
        private val localMutationsToPush: List<LocalModelMutation<SyncNote>>
    ) : ResourceSyncPlan {
        override val resourceName: String = this@NotesSyncAdapter.resourceName

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

private fun SyncMutation.toSyncNote(logger: Logger): SyncNote? {
    val id = resourceId ?: return null
    val lastModified = Instant.fromEpochMilliseconds(timestamp ?: 0)
    if (mutation == Mutation.DELETED) {
        return SyncNote(
            id = id,
            body = null,
            ranges = emptyList(),
            lastModified = lastModified
        )
    }

    val payload = data
    if (payload == null) {
        logger.w { "Skipping note mutation without data: resourceId=$resourceId" }
        return null
    }

    val body = payload.stringOrNull("body")
    if (body.isNullOrEmpty()) {
        logger.w { "Skipping note mutation without body: resourceId=$resourceId" }
        return null
    }

    val rangeStrings = payload.stringListOrNull("ranges")
    if (rangeStrings.isNullOrEmpty()) {
        logger.w { "Skipping note mutation without ranges: resourceId=$resourceId" }
        return null
    }

    val parsedRanges = rangeStrings.mapNotNull { range ->
        val parsed = parseNoteRange(range)
        if (parsed == null) {
            logger.w { "Skipping invalid note range=$range: resourceId=$resourceId" }
        }
        parsed
    }

    if (parsedRanges.isEmpty()) {
        logger.w { "Skipping note mutation without parsable ranges: resourceId=$resourceId" }
        return null
    }

    return SyncNote(
        id = id,
        body = body,
        ranges = parsedRanges,
        lastModified = lastModified
    )
}

private fun SyncNote.toResourceData(): JsonObject {
    val noteBody = requireNotNull(body) { "Note body is required for resource data." }
    require(ranges.isNotEmpty()) { "Note ranges are required for resource data." }
    return buildJsonObject {
        put("body", noteBody)
        put("ranges", buildJsonArray {
            ranges.forEach { range ->
                add(range.toRangeString())
            }
        })
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.stringListOrNull(key: String): List<String>? {
    val jsonArray = this[key]?.jsonArray ?: return null
    return jsonArray.mapNotNull { element -> element.jsonPrimitive.contentOrNull }
}
