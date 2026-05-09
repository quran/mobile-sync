@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.quran.shared.persistence.repository

import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.input.RemoteReadingSession
import com.quran.shared.persistence.model.ReadingSession
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepositoryImpl
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ReadingSessionsRepositoryTest {
    private lateinit var database: QuranDatabase
    private lateinit var repository: ReadingSessionsRepositoryImpl

    @BeforeTest
    fun setup() {
        database = QuranDatabase(TestDatabaseDriver().createDriver())
        repository = ReadingSessionsRepositoryImpl(database)
    }

    @Test
    fun `addReadingSession persists millisecond timestamps`() = runTest {
        repository = ReadingSessionsRepositoryImpl(database) { 1234567890123L }

        val readingSession = repository.addReadingSession(2, 255)
        val record = database.reading_sessionsQueries.getReadingSessionForChapterVerse(2L, 255L)
            .executeAsOne()

        assertEquals(1234567890123L, readingSession.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1234567890123L, record.created_at)
        assertEquals(1234567890123L, record.modified_at)
    }

    @Test
    fun `addReadingSession respects explicit timestamp`() = runTest {
        repository = ReadingSessionsRepositoryImpl(database) { 9999L }

        val readingSession = repository.addReadingSession(2, 255, timestamp(1234L))
        val record = database.reading_sessionsQueries.getReadingSessionForChapterVerse(2L, 255L)
            .executeAsOne()

        assertEquals(1234L, readingSession.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1234L, record.created_at)
        assertEquals(1234L, record.modified_at)
    }

    @Test
    fun `addReadingSession updates existing session with latest millisecond timestamp`() = runTest {
        var now = 1000L
        repository = ReadingSessionsRepositoryImpl(database) { now }

        repository.addReadingSession(2, 255)
        now = 1234567890123L
        val readingSession = repository.addReadingSession(2, 255)

        assertEquals(1234567890123L, readingSession.lastUpdated.fromPlatform().toEpochMilliseconds())
    }

    @Test
    fun `deleteReadingSession does not update remote row timestamp`() = runTest {
        repository = ReadingSessionsRepositoryImpl(database) { 1234567890123L }
        database.reading_sessionsQueries.persistRemoteReadingSession(
            remote_id = "remote-reading-session-id",
            chapter_number = 2L,
            verse_number = 255L,
            created_at = 1L,
            modified_at = 1L
        )

        repository.deleteReadingSession(2, 255)

        val localMutation = repository.fetchMutatedReadingSessions().single()
        val record = database.reading_sessionsQueries.getReadingSessionByRemoteId("remote-reading-session-id")
            .executeAsOne()
        assertEquals(Mutation.DELETED, localMutation.mutation)
        assertEquals(1L, localMutation.model.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1L, record.modified_at)
    }

    @Test
    fun `deleteReadingSession preserves timestamp for remote rows`() = runTest {
        repository = ReadingSessionsRepositoryImpl(database) { 9999L }
        database.reading_sessionsQueries.persistRemoteReadingSession(
            remote_id = "remote-reading-session-id",
            chapter_number = 2L,
            verse_number = 255L,
            created_at = 1L,
            modified_at = 1L
        )

        repository.deleteReadingSession(2, 255)

        val localMutation = repository.fetchMutatedReadingSessions().single()
        val record = database.reading_sessionsQueries.getReadingSessionByRemoteId("remote-reading-session-id")
            .executeAsOne()
        assertEquals(Mutation.DELETED, localMutation.mutation)
        assertEquals(1L, localMutation.model.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1L, record.modified_at)
    }

    @Test
    fun `updateReadingSession preserves remote_id and created_at`() = runTest {
        repository = ReadingSessionsRepositoryImpl(database) { 3000L }
        database.reading_sessionsQueries.persistRemoteReadingSession(
            remote_id = "remote-reading-session-id",
            chapter_number = 2L,
            verse_number = 255L,
            created_at = 1000L,
            modified_at = 2000L
        )
        val original = database.reading_sessionsQueries.getReadingSessionForChapterVerse(2L, 255L)
            .executeAsOne()

        val updated = repository.updateReadingSession(original.local_id.toString(), 3, 10)
        val record = database.reading_sessionsQueries.getReadingSessionByLocalId(original.local_id)
            .executeAsOne()

        assertEquals(original.local_id.toString(), updated.localId)
        assertEquals(original.local_id, record.local_id)
        assertEquals("remote-reading-session-id", record.remote_id)
        assertEquals(1000L, record.created_at)
        assertEquals(3L, record.chapter_number)
        assertEquals(10L, record.verse_number)
    }

    @Test
    fun `updateReadingSession updates modified_at in milliseconds`() = runTest {
        var now = 1000L
        repository = ReadingSessionsRepositoryImpl(database) { now }
        val readingSession = repository.addReadingSession(2, 255)

        now = 1234567890123L
        val updated = repository.updateReadingSession(readingSession.localId, 3, 10)
        val record = database.reading_sessionsQueries.getReadingSessionByLocalId(readingSession.localId.toLong())
            .executeAsOne()

        assertEquals(1234567890123L, updated.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1234567890123L, record.modified_at)
    }

    @Test
    fun `updateReadingSession respects explicit timestamp`() = runTest {
        repository = ReadingSessionsRepositoryImpl(database) { 9999L }
        val readingSession = repository.addReadingSession(2, 255, timestamp(1000L))

        val updated = repository.updateReadingSession(readingSession.localId, 3, 10, timestamp(3456L))
        val record = database.reading_sessionsQueries.getReadingSessionByLocalId(readingSession.localId.toLong())
            .executeAsOne()

        assertEquals(3456L, updated.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1000L, record.created_at)
        assertEquals(3456L, record.modified_at)
    }

    @Test
    fun `updateReadingSession marks remote-backed rows as edited`() = runTest {
        repository = ReadingSessionsRepositoryImpl(database) { 1234567890123L }
        database.reading_sessionsQueries.persistRemoteReadingSession(
            remote_id = "remote-reading-session-id",
            chapter_number = 2L,
            verse_number = 255L,
            created_at = 1000L,
            modified_at = 2000L
        )
        val original = database.reading_sessionsQueries.getReadingSessionForChapterVerse(2L, 255L)
            .executeAsOne()

        repository.updateReadingSession(original.local_id.toString(), 3, 10)

        val record = database.reading_sessionsQueries.getReadingSessionByLocalId(original.local_id)
            .executeAsOne()
        val localMutation = repository.fetchMutatedReadingSessions().single()
        assertEquals(1L, record.is_edited)
        assertEquals(0L, record.deleted)
        assertEquals(Mutation.MODIFIED, localMutation.mutation)
    }

    @Test
    fun `updateReadingSession keeps local-only rows unsynced without unnecessary edited flag`() = runTest {
        var now = 1000L
        repository = ReadingSessionsRepositoryImpl(database) { now }
        val readingSession = repository.addReadingSession(2, 255)

        now = 2000L
        repository.updateReadingSession(readingSession.localId, 3, 10)

        val record = database.reading_sessionsQueries.getReadingSessionByLocalId(readingSession.localId.toLong())
            .executeAsOne()
        val localMutation = repository.fetchMutatedReadingSessions().single()
        assertNull(record.remote_id)
        assertEquals(0L, record.is_edited)
        assertEquals(Mutation.CREATED, localMutation.mutation)
        assertNull(localMutation.remoteID)
    }

    @Test
    fun `updateReadingSession handles target conflicts deterministically`() = runTest {
        val source = repository.addReadingSession(2, 255)
        val target = repository.addReadingSession(3, 10)

        assertFailsWith<IllegalArgumentException> {
            repository.updateReadingSession(source.localId, 3, 10)
        }

        val sourceRecord = database.reading_sessionsQueries.getReadingSessionByLocalId(source.localId.toLong())
            .executeAsOne()
        val targetRecord = database.reading_sessionsQueries.getReadingSessionForChapterVerse(3L, 10L)
            .executeAsOne()
        assertEquals(2L, sourceRecord.chapter_number)
        assertEquals(255L, sourceRecord.verse_number)
        assertEquals(target.localId.toLong(), targetRecord.local_id)
    }

    @Test
    fun `addReadingSession implicitly keeps newest twenty sessions`() = runTest {
        var now = 0L
        repository = ReadingSessionsRepositoryImpl(database) { now }
        (1..21).forEach { ayah ->
            now = ayah * 1000L
            repository.addReadingSession(2, ayah)
        }

        val remaining = repository.getReadingSessions()

        assertEquals((21 downTo 2).toList(), remaining.map { it.ayah })
    }

    @Test
    fun `addReadingSession hard-deletes local-only extras beyond newest twenty`() = runTest {
        var now = 0L
        repository = ReadingSessionsRepositoryImpl(database) { now }
        (1..21).forEach { ayah ->
            now = ayah * 1000L
            repository.addReadingSession(2, ayah)
        }

        assertNull(database.reading_sessionsQueries.getReadingSessionForChapterVerse(2L, 1L).executeAsOneOrNull())
        assertEquals((21 downTo 2).toList(), repository.getReadingSessions().map { it.ayah })
    }

    @Test
    fun `addReadingSession marks remote-backed extras deleted and edited beyond newest twenty`() = runTest {
        repository = ReadingSessionsRepositoryImpl(database) { 21000L }
        (1..20).forEach { ayah ->
            database.reading_sessionsQueries.persistRemoteReadingSession(
                remote_id = "remote-reading-session-$ayah",
                chapter_number = 2L,
                verse_number = ayah.toLong(),
                created_at = ayah * 1000L,
                modified_at = ayah * 1000L
            )
        }

        repository.addReadingSession(2, 21)
        val remaining = repository.getReadingSessions()

        val pruned = database.reading_sessionsQueries
            .getReadingSessionByRemoteId("remote-reading-session-1")
            .executeAsOne()
        assertEquals((21 downTo 2).toList(), remaining.map { it.ayah })
        assertEquals(1L, pruned.deleted)
        assertEquals(1L, pruned.is_edited)
        assertEquals(1000L, pruned.modified_at)
    }

    @Test
    fun `reading sessions flow emits final state after update and implicit prune`() = runTest {
        var now = 0L
        repository = ReadingSessionsRepositoryImpl(database) { now }
        lateinit var oldest: ReadingSession
        (1..20).forEach { ayah ->
            now = ayah * 1000L
            val session = repository.addReadingSession(2, ayah)
            if (ayah == 1) {
                oldest = session
            }
        }

        val emissions = Channel<List<ReadingSession>>(Channel.UNLIMITED)
        val job = launch {
            repository.getReadingSessionsFlow().collect {
                emissions.send(it)
            }
        }
        runCurrent()

        suspend fun receiveEmission(): List<ReadingSession> {
            return withContext(Dispatchers.Default) {
                withTimeout(5000) { emissions.receive() }
            }
        }

        try {
            assertEquals((20 downTo 1).toList(), receiveEmission().map { it.ayah })

            now = 21000L
            repository.updateReadingSession(oldest.localId, 2, 21)

            assertEquals((21 downTo 2).toList(), receiveEmission().map { it.ayah })

            now = 22000L
            repository.addReadingSession(2, 22)

            assertEquals((22 downTo 3).toList(), receiveEmission().map { it.ayah })
        } finally {
            job.cancelAndJoin()
            emissions.close()
        }
    }

    @Test
    fun `applyRemoteChanges removes deleted remote reading session after successful sync`() = runTest {
        database.reading_sessionsQueries.persistRemoteReadingSession(
            remote_id = "remote-reading-session-id",
            chapter_number = 2L,
            verse_number = 255L,
            created_at = 1L,
            modified_at = 1L
        )

        repository.deleteReadingSession(2, 255)

        val localMutations = repository.fetchMutatedReadingSessions()
        assertEquals(1, localMutations.size)
        assertEquals(Mutation.DELETED, localMutations.single().mutation)

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingSession(
                        chapterNumber = 2,
                        verseNumber = 255,
                        lastUpdated = Instant.fromEpochMilliseconds(1000L).toPlatform()
                    ),
                    remoteID = "remote-reading-session-id",
                    mutation = Mutation.DELETED
                )
            ),
            localMutationIdsToClear = localMutations.map { it.localID }
        )

        assertTrue(repository.getReadingSessions().isEmpty())
        assertNull(database.reading_sessionsQueries.getReadingSessionForChapterVerse(2L, 255L).executeAsOneOrNull())
    }

    @Test
    fun `applyRemoteChanges persists remote reading session using matching local session`() = runTest {
        val localReadingSession = repository.addReadingSession(2, 255)

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingSession(
                        chapterNumber = 2,
                        verseNumber = 255,
                        lastUpdated = Instant.fromEpochMilliseconds(1000L).toPlatform()
                    ),
                    remoteID = "remote-reading-session-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationIdsToClear = listOf(localReadingSession.localId)
        )

        val readingSessions = repository.getReadingSessions()
        assertEquals(1, readingSessions.size)
        assertEquals(2, readingSessions.single().sura)
        assertEquals(255, readingSessions.single().ayah)
    }

    @Test
    fun `applyRemoteChanges persists remote reading session without local row`() = runTest {
        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingSession(
                        chapterNumber = 2,
                        verseNumber = 255,
                        lastUpdated = Instant.fromEpochMilliseconds(1000L).toPlatform()
                    ),
                    remoteID = "remote-reading-session-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationIdsToClear = emptyList()
        )

        val readingSessions = repository.getReadingSessions()
        assertEquals(1, readingSessions.size)
        assertEquals(2, readingSessions.single().sura)
        assertEquals(255, readingSessions.single().ayah)
        assertEquals(
            "remote-reading-session-id",
            database.reading_sessionsQueries.getReadingSessionForChapterVerse(2L, 255L)
                .executeAsOne()
                .remote_id
        )
    }

    @Test
    fun `applyRemoteChanges implicitly keeps newest twenty reading sessions`() = runTest {
        repository = ReadingSessionsRepositoryImpl(database) { 22000L }

        repository.applyRemoteChanges(
            updatesToPersist = (1..21).map { ayah ->
                RemoteModelMutation(
                    model = RemoteReadingSession(
                        chapterNumber = 2,
                        verseNumber = ayah,
                        lastUpdated = Instant.fromEpochMilliseconds(ayah * 1000L).toPlatform()
                    ),
                    remoteID = "remote-reading-session-$ayah",
                    mutation = Mutation.CREATED
                )
            },
            localMutationIdsToClear = emptyList()
        )

        val pruned = database.reading_sessionsQueries
            .getReadingSessionByRemoteId("remote-reading-session-1")
            .executeAsOne()
        assertEquals((21 downTo 2).toList(), repository.getReadingSessions().map { it.ayah })
        assertEquals(1L, pruned.deleted)
        assertEquals(1L, pruned.is_edited)
        assertEquals(1000L, pruned.modified_at)
    }

    private fun timestamp(milliseconds: Long) = Instant.fromEpochMilliseconds(milliseconds).toPlatform()
}
