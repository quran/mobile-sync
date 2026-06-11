package com.quran.shared.persistence.repository.readingsession.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.touchlab.kermit.Logger
import com.quran.shared.di.AppScope
import com.quran.shared.mutations.LOCAL_MUTATION_ENTITY_FACET
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.LocalMutationResource
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.input.RemoteReadingSession
import com.quran.shared.persistence.model.DatabaseReadingSession
import com.quran.shared.persistence.model.ReadingSession
import com.quran.shared.persistence.repository.PersistenceWriteBoundaryGuard
import com.quran.shared.persistence.repository.buildRemoteResourceExistenceMap
import com.quran.shared.persistence.repository.readingsession.extension.toReadingSession
import com.quran.shared.persistence.repository.readingsession.extension.toReadingSessionMutation
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toEpochMillisecondsOrNull
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Inject
@SingleIn(AppScope::class)
class ReadingSessionsRepositoryImpl(
    private val database: QuranDatabase
) : ReadingSessionsRepository, ReadingSessionsSynchronizationRepository {

    private companion object {
        const val MAX_ACTIVE_READING_SESSIONS = 20L
    }

    private val logger = Logger.withTag("ReadingSessionsRepository")
    private val readingSessionsQueries = lazy { database.reading_sessionsQueries }
    private var currentTimeMillis: () -> Long = ::currentEpochMilliseconds

    internal constructor(
        database: QuranDatabase,
        currentTimeMillis: () -> Long
    ) : this(database) {
        this.currentTimeMillis = currentTimeMillis
    }

    override suspend fun getReadingSessions(): List<ReadingSession> {
        return withContext(Dispatchers.IO) {
            readingSessionsQueries.value.getReadingSessions()
                .executeAsList()
                .map { it.toReadingSession() }
        }
    }

    override fun getReadingSessionsFlow(): Flow<List<ReadingSession>> {
        return readingSessionsQueries.value.getReadingSessions()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list -> list.map { it.toReadingSession() } }
    }

    override suspend fun addReadingSession(sura: Int, ayah: Int): ReadingSession {
        return addReadingSessionWithTimestampMillis(sura, ayah, timestampMillis = null)
    }

    override suspend fun addReadingSession(sura: Int, ayah: Int, timestamp: PlatformDateTime): ReadingSession {
        return addReadingSessionWithTimestampMillis(sura, ayah, timestamp.toEpochMillisecondsOrNull())
    }

    private suspend fun addReadingSessionWithTimestampMillis(
        sura: Int,
        ayah: Int,
        timestampMillis: Long?
    ): ReadingSession {
        logger.i { "Adding reading session ($sura:$ayah)" }
        return withContext(Dispatchers.IO) {
            val updatedAt = timestampMillis ?: currentTimeMillis()
            var readingSession: ReadingSession? = null

            database.transaction {
                readingSessionsQueries.value.addReadingSession(
                    chapter_number = sura.toLong(),
                    verse_number = ayah.toLong(),
                    modified_at = updatedAt
                )
                pruneOldReadingSessions()

                val record = readingSessionsQueries.value.getReadingSessionForChapterVerse(
                    sura.toLong(),
                    ayah.toLong()
                )
                    .executeAsOneOrNull()
                requireNotNull(record) { "Expected reading session for $sura:$ayah after insert." }
                readingSession = record.toReadingSession()
            }

            requireNotNull(readingSession)
        }
    }

    override suspend fun updateReadingSession(localId: String, sura: Int, ayah: Int): ReadingSession {
        return updateReadingSessionWithTimestampMillis(localId, sura, ayah, timestampMillis = null)
    }

    override suspend fun updateReadingSession(
        localId: String,
        sura: Int,
        ayah: Int,
        timestamp: PlatformDateTime
    ): ReadingSession {
        return updateReadingSessionWithTimestampMillis(localId, sura, ayah, timestamp.toEpochMillisecondsOrNull())
    }

    private suspend fun updateReadingSessionWithTimestampMillis(
        localId: String,
        sura: Int,
        ayah: Int,
        timestampMillis: Long?
    ): ReadingSession {
        logger.i { "Updating reading session localId=$localId to $sura:$ayah" }
        return withContext(Dispatchers.IO) {
            val id = localId.toLong()
            val updatedAt = timestampMillis ?: currentTimeMillis()
            var updatedSession: ReadingSession? = null

            database.transaction {
                val existing = readingSessionsQueries.value.getReadingSessionByLocalId(id)
                    .executeAsOneOrNull()
                requireNotNull(existing) { "Expected reading session localId=$localId before update." }

                val conflicting = readingSessionsQueries.value.getReadingSessionForChapterVerse(
                    sura.toLong(),
                    ayah.toLong()
                ).executeAsOneOrNull()
                require(conflicting == null || conflicting.local_id == id) {
                    "Reading session already exists for $sura:$ayah."
                }

                readingSessionsQueries.value.updateReadingSession(
                    local_id = id,
                    chapter_number = sura.toLong(),
                    verse_number = ayah.toLong(),
                    modified_at = updatedAt
                )
                pruneOldReadingSessions()

                val record = readingSessionsQueries.value.getReadingSessionByLocalId(id)
                    .executeAsOneOrNull()
                requireNotNull(record) { "Expected reading session localId=$localId after update." }
                updatedSession = record.toReadingSession()
            }

            requireNotNull(updatedSession)
        }
    }

    override suspend fun deleteReadingSession(sura: Int, ayah: Int): Boolean {
        logger.i { "Deleting reading session for $sura:$ayah" }
        withContext(Dispatchers.IO) {
            readingSessionsQueries.value.deleteReadingSession(
                chapter_number = sura.toLong(),
                verse_number = ayah.toLong()
            )
        }
        return true
    }

    override suspend fun fetchMutatedReadingSessions(): List<LocalModelMutation<ReadingSession>> {
        return withContext(Dispatchers.IO) {
            readingSessionsQueries.value.getUnsyncedReadingSessions()
                .executeAsList()
                .map { it.toReadingSessionMutation() }
        }
    }

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteReadingSession>>,
        localMutationIdsToClear: List<String>,
        writeBoundaryGuard: PersistenceWriteBoundaryGuard
    ) {
        require(localMutationIdsToClear.isEmpty()) {
            "Reading session ID-only local mutation clears are unsafe; use applyRemoteChangesForMutations."
        }
        applyRemoteChangesForMutations(updatesToPersist, emptyList(), writeBoundaryGuard)
    }

    override suspend fun applyRemoteChangesForMutations(
        updatesToPersist: List<RemoteModelMutation<RemoteReadingSession>>,
        localMutationsToClear: List<LocalModelMutation<ReadingSession>>,
        writeBoundaryGuard: PersistenceWriteBoundaryGuard
    ) {
        logger.i {
            "Applying remote changes for reading sessions: updates=${updatesToPersist.size}, " +
                "toClear=${localMutationsToClear.size}"
        }
        return withContext(Dispatchers.IO) {
            writeBoundaryGuard.checkWriteBoundary()
            database.transaction {
                localMutationsToClear.forEach { local ->
                    if (local.mutation != Mutation.CREATED && ackMatchesCurrentRow(local)) {
                        clearLocalMutation(local)
                    }
                }

                // Apply remote updates
                updatesToPersist.forEach { remote ->
                    when (remote.mutation) {
                        Mutation.CREATED, Mutation.MODIFIED -> {
                            applyRemoteReadingSessionUpsert(remote)
                        }
                        Mutation.DELETED -> {
                            applyRemoteReadingSessionDeletion(remote)
                        }
                    }
                }

                pruneOldReadingSessions(incrementPendingVersion = false)
            }
        }
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> {
        return buildRemoteResourceExistenceMap(remoteIDs) { chunk ->
            readingSessionsQueries.value.checkRemoteIDsExistence(chunk)
                .executeAsList()
                .mapNotNull { it.remote_id }
        }
    }

    override suspend fun fetchReadingSessionByRemoteId(remoteId: String): ReadingSession? {
        return withContext(Dispatchers.IO) {
            readingSessionsQueries.value.getReadingSessionByRemoteId(remoteId)
                .executeAsOneOrNull()
                ?.toReadingSession()
        }
    }

    private fun applyRemoteReadingSessionUpsert(remote: RemoteModelMutation<RemoteReadingSession>) {
        val model = remote.model
        val existingByRemote = readingSessionsQueries.value.getReadingSessionByRemoteId(remote.remoteID)
            .executeAsOneOrNull()
        if (existingByRemote?.hasPendingLocalMutation() == true) {
            logger.i { "Skipping remote reading session upsert for pending local row: remoteId=${remote.remoteID}" }
            return
        }

        if (remote.mutation == Mutation.CREATED) {
            val createdAck = remote.createdAckOrNull()
            val updatedAt = model.lastUpdated.fromPlatform().toEpochMilliseconds()
            if (createdAck != null && attachRemoteIdForCreatedAck(remote, createdAck, updatedAt)) {
                return
            }
        }

        val chapterNumber = model.chapterNumber.toLong()
        val verseNumber = model.verseNumber.toLong()
        val existingByPosition = readingSessionsQueries.value.getReadingSessionForChapterVerse(
            chapterNumber,
            verseNumber
        ).executeAsOneOrNull()
        if (remote.mutation == Mutation.CREATED) {
            val deletedSemanticCandidates = readingSessionsQueries.value
                .getDeletedPendingCreatedReadingSessionsForChapterVerse(chapterNumber, verseNumber)
                .executeAsList()
            when (deletedSemanticCandidates.size) {
                1 -> if (attachRemoteIdForSemanticReplay(remote, deletedSemanticCandidates.single().local_id)) {
                    return
                }
                in 2..Int.MAX_VALUE -> throw IllegalStateException(
                    "Ambiguous deleted reading session semantic replay candidates for remoteId=${remote.remoteID}"
                )
                0 -> {
                    val semanticCandidates = readingSessionsQueries.value
                        .getPendingCreatedReadingSessionsForChapterVerse(chapterNumber, verseNumber)
                        .executeAsList()
                    if (semanticCandidates.size == 1 &&
                        attachRemoteIdForSemanticReplay(remote, semanticCandidates.single().local_id)
                    ) {
                        return
                    }
                }
            }
        }
        if (existingByPosition?.remote_id == null &&
            existingByPosition != null) {
            logger.i {
                "Skipping remote reading session attach for unacknowledged local row: " +
                    "localId=${existingByPosition.local_id}"
            }
            return
        }
        if (existingByPosition?.remote_id != null && existingByPosition.hasPendingLocalMutation()) {
            logger.i { "Skipping remote reading session attach for pending local row: localId=${existingByPosition.local_id}" }
            return
        }

        val updatedAt = model.lastUpdated.fromPlatform().toEpochMilliseconds()
        logger.i {
            "Persisting remote reading session: remoteId=${remote.remoteID}, " +
                "chapter=${model.chapterNumber}, verse=${model.verseNumber}"
        }
        readingSessionsQueries.value.persistRemoteReadingSession(
            remote_id = remote.remoteID,
            chapter_number = model.chapterNumber.toLong(),
            verse_number = model.verseNumber.toLong(),
            created_at = updatedAt,
            modified_at = updatedAt
        )
    }

    private fun applyRemoteReadingSessionDeletion(remote: RemoteModelMutation<RemoteReadingSession>) {
        val existing = readingSessionsQueries.value.getReadingSessionByRemoteId(remote.remoteID)
            .executeAsOneOrNull()
        if (existing?.hasPendingLocalMutation() == true) {
            logger.i { "Skipping remote reading session deletion for pending local row: remoteId=${remote.remoteID}" }
            return
        }
        readingSessionsQueries.value.hardDeleteReadingSessionFor(remoteID = remote.remoteID)
    }

    private fun attachRemoteIdForCreatedAck(
        remote: RemoteModelMutation<RemoteReadingSession>,
        ack: CreatedReadingSessionAck,
        updatedAt: Long
    ): Boolean {
        val row = readingSessionsQueries.value.getReadingSessionByLocalId(ack.localId)
            .executeAsOneOrNull()
        if (row?.remote_id != null) {
            return false
        }
        readingSessionsQueries.value.attachRemoteReadingSessionIdForCreatedAck(
            local_id = ack.localId,
            remote_id = remote.remoteID,
            pending_version = ack.pendingVersion,
            modified_at = updatedAt
        )
        val attached = readingSessionsQueries.value.getReadingSessionByLocalId(ack.localId)
            .executeAsOneOrNull()
        return attached?.remote_id == remote.remoteID
    }

    private fun attachRemoteIdForSemanticReplay(
        remote: RemoteModelMutation<RemoteReadingSession>,
        localId: Long
    ): Boolean {
        val updatedAt = remote.model.lastUpdated.fromPlatform().toEpochMilliseconds()
        readingSessionsQueries.value.attachRemoteReadingSessionIdForSemanticReplay(
            local_id = localId,
            remote_id = remote.remoteID,
            modified_at = updatedAt
        )
        val attached = readingSessionsQueries.value.getReadingSessionByLocalId(localId)
            .executeAsOneOrNull()
        return attached?.remote_id == remote.remoteID
    }

    private fun clearLocalMutation(local: LocalModelMutation<ReadingSession>) {
        val ack = local.ack ?: return
        readingSessionsQueries.value.clearLocalMutationFor(
            id = local.localID.toLong(),
            modified_at = currentTimeMillis(),
            pending_version = ack.observedPendingVersion,
            pending_op = ack.observedPendingOp.name
        )
    }

    private fun ackMatchesCurrentRow(local: LocalModelMutation<ReadingSession>): Boolean {
        val ack = local.ack ?: return false
        if (ack.localID != local.localID ||
            ack.resource != LocalMutationResource.READING_SESSION ||
            ack.facet != LOCAL_MUTATION_ENTITY_FACET ||
            ack.observedPendingOp != local.mutation) {
            return false
        }
        val row = readingSessionsQueries.value.getReadingSessionByLocalId(local.localID.toLong())
            .executeAsOneOrNull()
            ?: return false
        return row.pending_version == ack.observedPendingVersion &&
            row.pendingMutation() == ack.observedPendingOp
    }

    private fun DatabaseReadingSession.pendingMutation(): Mutation? {
        return when {
            remote_id == null -> Mutation.CREATED
            deleted == 1L -> Mutation.DELETED
            is_edited == 1L -> Mutation.MODIFIED
            else -> null
        }
    }

    private fun DatabaseReadingSession.hasPendingLocalMutation(): Boolean = pendingMutation() != null

    private fun RemoteModelMutation<RemoteReadingSession>.createdAckOrNull(): CreatedReadingSessionAck? {
        val ack = ack ?: return null
        if (mutation != Mutation.CREATED ||
            ack.resource != LocalMutationResource.READING_SESSION ||
            ack.facet != LOCAL_MUTATION_ENTITY_FACET ||
            ack.observedPendingOp != Mutation.CREATED) {
            return null
        }
        return CreatedReadingSessionAck(
            localId = ack.localID.toLong(),
            pendingVersion = ack.observedPendingVersion
        )
    }

    private fun pruneOldReadingSessions(incrementPendingVersion: Boolean = true) {
        if (incrementPendingVersion) {
            readingSessionsQueries.value.pruneLocalReadingSessions(MAX_ACTIVE_READING_SESSIONS)
            readingSessionsQueries.value.pruneRemoteReadingSessions(MAX_ACTIVE_READING_SESSIONS)
        } else {
            readingSessionsQueries.value.pruneLocalReadingSessionsForRemoteApply(MAX_ACTIVE_READING_SESSIONS)
            readingSessionsQueries.value.pruneRemoteReadingSessionsForRemoteApply(MAX_ACTIVE_READING_SESSIONS)
        }
    }

    private data class CreatedReadingSessionAck(
        val localId: Long,
        val pendingVersion: Long
    )
}

@OptIn(ExperimentalTime::class)
private fun currentEpochMilliseconds(): Long = Clock.System.now().toEpochMilliseconds()
