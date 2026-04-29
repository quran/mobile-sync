package com.quran.shared.persistence.repository.readingsession.repository

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
import com.quran.shared.persistence.util.SQLITE_MAX_BIND_PARAMETERS
import com.quran.shared.persistence.util.fromPlatform
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList

@Inject
@SingleIn(AppScope::class)
class ReadingSessionsRepositoryImpl(
    private val database: QuranDatabase
) : ReadingSessionsRepository, ReadingSessionsSynchronizationRepository {

    private val logger = Logger.withTag("ReadingSessionsRepository")
    private val readingSessionsQueries = lazy { database.reading_sessionsQueries }

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

    override suspend fun addReadingSession(chapterNumber: Int, verseNumber: Int): ReadingSession {
        logger.i { "Adding reading session ($chapterNumber:$verseNumber)" }
        return withContext(Dispatchers.IO) {
            readingSessionsQueries.value.addReadingSession(
                chapter_number = chapterNumber.toLong(),
                verse_number = verseNumber.toLong()
            )
            val record = readingSessionsQueries.value.getReadingSessionForChapterVerse(
                chapterNumber.toLong(),
                verseNumber.toLong()
            )
                .executeAsOneOrNull()
            requireNotNull(record) { "Expected reading session for $chapterNumber:$verseNumber after insert." }
            record.toReadingSession()
        }
    }

    override suspend fun deleteReadingSession(chapterNumber: Int, verseNumber: Int): Boolean {
        logger.i { "Deleting reading session for $chapterNumber:$verseNumber" }
        withContext(Dispatchers.IO) {
            readingSessionsQueries.value.deleteReadingSession(
                chapter_number = chapterNumber.toLong(),
                verse_number = verseNumber.toLong()
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
                            val existingSession = readingSessionsQueries.value.getReadingSessionByRemoteId(remote.remoteID)
                                .executeAsOneOrNull()
                                ?: readingSessionsQueries.value.getReadingSessions()
                                    .executeAsList()
                                    .firstOrNull { record ->
                                        record.chapter_number.toInt() == model.chapterNumber &&
                                            record.verse_number.toInt() == model.verseNumber
                                    }
                            if (existingSession == null) {
                                logger.w {
                                    "Skipping reading session mutation without local session match: " +
                                        "remoteId=${remote.remoteID}, chapter=${model.chapterNumber}, verse=${model.verseNumber}"
                                }
                                return@forEach
                            }
                            val updatedAt = model.lastUpdated.fromPlatform().toEpochMilliseconds()
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
                    readingSessionsQueries.value.clearLocalMutationFor(id = localId.toLong())
                }
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
}
