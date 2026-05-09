package com.quran.shared.persistence.repository.readingsession.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.touchlab.kermit.Logger
import com.quran.shared.di.AppScope
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.input.RemoteReadingSession
import com.quran.shared.persistence.model.ReadingSession
import com.quran.shared.persistence.repository.readingsession.extension.toReadingSession
import com.quran.shared.persistence.repository.readingsession.extension.toReadingSessionMutation
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.SQLITE_MAX_BIND_PARAMETERS
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
        localMutationIdsToClear: List<String>
    ) {
        logger.i {
            "Applying remote changes for reading sessions: updates=${updatesToPersist.size}, " +
                "toClear=${localMutationIdsToClear.size}"
        }
        return withContext(Dispatchers.IO) {
            database.transaction {
                // Apply remote updates
                updatesToPersist.forEach { remote ->
                    when (remote.mutation) {
                        Mutation.CREATED, Mutation.MODIFIED -> {
                            val model = remote.model
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
                        Mutation.DELETED -> {
                            readingSessionsQueries.value.hardDeleteReadingSessionFor(remoteID = remote.remoteID)
                        }
                    }
                }

                // Clear local mutations after remote upserts so newly-synced rows keep their remote IDs.
                localMutationIdsToClear.forEach { localId ->
                    readingSessionsQueries.value.clearLocalMutationFor(
                        id = localId.toLong(),
                        modified_at = currentTimeMillis()
                    )
                }

                pruneOldReadingSessions()
            }
        }
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> {
        if (remoteIDs.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            val existentIDs = mutableSetOf<String>()
            remoteIDs.chunked(SQLITE_MAX_BIND_PARAMETERS).forEach { chunk ->
                existentIDs.addAll(
                    readingSessionsQueries.value.checkRemoteIDsExistence(chunk)
                        .executeAsList()
                        .mapNotNull { it.remote_id }
                )
            }
            remoteIDs.associateWith { existentIDs.contains(it) }
        }
    }

    override suspend fun fetchReadingSessionByRemoteId(remoteId: String): ReadingSession? {
        return withContext(Dispatchers.IO) {
            readingSessionsQueries.value.getReadingSessionByRemoteId(remoteId)
                .executeAsOneOrNull()
                ?.toReadingSession()
        }
    }

    private fun pruneOldReadingSessions() {
        readingSessionsQueries.value.getReadingSessionsToPrune(MAX_ACTIVE_READING_SESSIONS)
            .executeAsList()
            .forEach { session ->
                readingSessionsQueries.value.pruneReadingSessionByLocalId(
                    local_id = session.local_id
                )
            }
    }
}

@OptIn(ExperimentalTime::class)
private fun currentEpochMilliseconds(): Long = Clock.System.now().toEpochMilliseconds()
