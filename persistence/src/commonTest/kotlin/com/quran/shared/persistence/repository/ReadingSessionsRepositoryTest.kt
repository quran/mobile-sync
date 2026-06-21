@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.quran.shared.persistence.repository

import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.input.ImportReadingSession
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.input.RemoteReadingSession
import com.quran.shared.persistence.model.ReadingSession
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
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
import kotlin.test.assertFalse
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
    fun `deleteReadingSession returns false when no active row exists`() = runTest {
        val deleted = repository.deleteReadingSession(2, 255)

        assertFalse(deleted)
        assertEquals(emptyList(), repository.getReadingSessions())
        assertEquals(emptyList(), repository.fetchMutatedReadingSessions())
    }

    @Test
    fun `deleteReadingSession returns false for already deleted row without advancing mutation`() = runTest {
        database.reading_sessionsQueries.persistRemoteReadingSession(
            remote_id = "remote-reading-session-id",
            chapter_number = 2L,
            verse_number = 255L,
            created_at = 1L,
            modified_at = 1L
        )
        assertTrue(repository.deleteReadingSession(2, 255))
        val firstTombstone = database.reading_sessionsQueries
            .getReadingSessionByRemoteId("remote-reading-session-id")
            .executeAsOne()

        val deletedAgain = repository.deleteReadingSession(2, 255)

        val secondTombstone = database.reading_sessionsQueries
            .getReadingSessionByRemoteId("remote-reading-session-id")
            .executeAsOne()
        assertFalse(deletedAgain)
        assertEquals(firstTombstone.pending_version, secondTombstone.pending_version)
        assertEquals(firstTombstone.modified_at, secondTombstone.modified_at)
        assertEquals(1, repository.fetchMutatedReadingSessions().size)
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
    fun `updateReadingSession rejects deleted tombstones without resurrecting them`() = runTest {
        database.reading_sessionsQueries.persistRemoteReadingSession(
            remote_id = "remote-reading-session-id",
            chapter_number = 2L,
            verse_number = 255L,
            created_at = 1000L,
            modified_at = 1000L
        )
        val original = database.reading_sessionsQueries.getReadingSessionForChapterVerse(2L, 255L)
            .executeAsOne()
        assertTrue(repository.deleteReadingSession(2, 255))
        val tombstone = database.reading_sessionsQueries
            .getReadingSessionByRemoteId("remote-reading-session-id")
            .executeAsOne()

        assertFailsWith<IllegalArgumentException> {
            repository.updateReadingSession(original.local_id.toString(), 3, 10)
        }

        val unchanged = database.reading_sessionsQueries
            .getReadingSessionByRemoteId("remote-reading-session-id")
            .executeAsOne()
        assertEquals(1L, unchanged.deleted)
        assertEquals(1L, unchanged.is_edited)
        assertEquals(2L, unchanged.chapter_number)
        assertEquals(255L, unchanged.verse_number)
        assertEquals(tombstone.pending_version, unchanged.pending_version)
        assertEquals(tombstone.modified_at, unchanged.modified_at)

        val localMutation = repository.fetchMutatedReadingSessions().single()
        assertEquals(Mutation.DELETED, localMutation.mutation)
        assertEquals("remote-reading-session-id", localMutation.remoteID)
    }

    @Test
    fun `updateReadingSession rejects deleted local-created tombstones without resurrecting them`() = runTest {
        val created = repository.addReadingSession(2, 255)
        assertTrue(repository.deleteReadingSession(2, 255))
        val tombstone = database.reading_sessionsQueries
            .getReadingSessionByLocalId(created.localId.toLong())
            .executeAsOne()

        assertFailsWith<IllegalArgumentException> {
            repository.updateReadingSession(created.localId, 3, 10)
        }

        val unchanged = database.reading_sessionsQueries
            .getReadingSessionByLocalId(created.localId.toLong())
            .executeAsOne()
        assertEquals(1L, unchanged.deleted)
        assertEquals(0L, unchanged.is_edited)
        assertEquals(2L, unchanged.chapter_number)
        assertEquals(255L, unchanged.verse_number)
        assertEquals(tombstone.pending_version, unchanged.pending_version)
        assertEquals(tombstone.modified_at, unchanged.modified_at)
        assertEquals(emptyList(), repository.fetchMutatedReadingSessions())
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
    fun `addReadingSession tombstones local-created extras beyond newest twenty`() = runTest {
        var now = 0L
        repository = ReadingSessionsRepositoryImpl(database) { now }
        lateinit var oldest: ReadingSession
        (1..21).forEach { ayah ->
            now = ayah * 1000L
            val session = repository.addReadingSession(2, ayah)
            if (ayah == 1) {
                oldest = session
            }
        }

        val pruned = database.reading_sessionsQueries
            .getReadingSessionByLocalId(oldest.localId.toLong())
            .executeAsOne()
        assertEquals(1L, pruned.deleted)
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

        repository.applyRemoteChangesForMutations(
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
            localMutationsToClear = localMutations
        )

        assertTrue(repository.getReadingSessions().isEmpty())
        assertNull(database.reading_sessionsQueries.getReadingSessionForChapterVerse(2L, 255L).executeAsOneOrNull())
    }

    @Test
    fun `applyRemoteChanges persists remote reading session using matching ACK`() = runTest {
        val localReadingSession = repository.addReadingSession(2, 255)
        val localMutations = repository.fetchMutatedReadingSessions()

        repository.applyRemoteChangesForMutations(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingSession(
                        chapterNumber = 2,
                        verseNumber = 255,
                        lastUpdated = Instant.fromEpochMilliseconds(1000L).toPlatform()
                    ),
                    remoteID = "remote-reading-session-id",
                    mutation = Mutation.CREATED,
                    ack = localMutations.single().ack
                )
            ),
            localMutationsToClear = localMutations
        )

        val readingSessions = repository.getReadingSessions()
        assertEquals(1, readingSessions.size)
        assertEquals(2, readingSessions.single().sura)
        assertEquals(255, readingSessions.single().ayah)
        assertEquals(localReadingSession.localId, readingSessions.single().localId)
        assertEquals(emptyList(), repository.fetchMutatedReadingSessions())
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
    fun `applyRemoteChanges checks write boundary before reading session transaction`() = runTest {
        assertFailsWith<IllegalStateException> {
            repository.applyRemoteChangesForMutations(
                updatesToPersist = listOf(
                    RemoteModelMutation(
                        model = RemoteReadingSession(
                            chapterNumber = 2,
                            verseNumber = 255,
                            lastUpdated = Instant.fromEpochMilliseconds(2000L).toPlatform()
                        ),
                        remoteID = "remote-reading-session-id",
                        mutation = Mutation.CREATED
                    )
                ),
                localMutationsToClear = emptyList(),
                writeBoundaryGuard = PersistenceWriteBoundaryGuard {
                    throw IllegalStateException("stale epoch")
                }
            )
        }

        assertNull(
            database.reading_sessionsQueries.getReadingSessionByRemoteId("remote-reading-session-id")
                .executeAsOneOrNull()
        )
    }

    @Test
    fun `legacy applyRemoteChanges rejects unsafe reading session ID-only clears`() = runTest {
        val readingSession = repository.addReadingSession(2, 255)

        assertFailsWith<IllegalArgumentException> {
            repository.applyRemoteChanges(
                updatesToPersist = emptyList(),
                localMutationIdsToClear = listOf(readingSession.localId)
            )
        }
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

    @Test
    fun `applyRemoteChanges clears reading session ACK when pending version still matches`() = runTest {
        database.reading_sessionsQueries.persistRemoteReadingSession(
            remote_id = "remote-reading-session-id",
            chapter_number = 2L,
            verse_number = 255L,
            created_at = 1000L,
            modified_at = 1000L
        )
        val original = database.reading_sessionsQueries.getReadingSessionByRemoteId("remote-reading-session-id")
            .executeAsOne()
        repository.updateReadingSession(original.local_id.toString(), 3, 10, timestamp(2000L))
        val mutation = repository.fetchMutatedReadingSessions().single()

        repository.applyRemoteChangesForMutations(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingSession(
                        chapterNumber = 3,
                        verseNumber = 10,
                        lastUpdated = timestamp(2000L)
                    ),
                    remoteID = "remote-reading-session-id",
                    mutation = Mutation.MODIFIED
                )
            ),
            localMutationsToClear = listOf(mutation)
        )

        val record = database.reading_sessionsQueries.getReadingSessionByRemoteId("remote-reading-session-id")
            .executeAsOne()
        assertEquals(3L, record.chapter_number)
        assertEquals(10L, record.verse_number)
        assertEquals(0L, record.is_edited)
        assertEquals(emptyList(), repository.fetchMutatedReadingSessions())
    }

    @Test
    fun `applyRemoteChanges does not clear stale reading session ACK after newer local write`() = runTest {
        database.reading_sessionsQueries.persistRemoteReadingSession(
            remote_id = "remote-reading-session-id",
            chapter_number = 2L,
            verse_number = 255L,
            created_at = 1000L,
            modified_at = 1000L
        )
        val original = database.reading_sessionsQueries.getReadingSessionByRemoteId("remote-reading-session-id")
            .executeAsOne()
        repository.updateReadingSession(original.local_id.toString(), 3, 10, timestamp(2000L))
        val staleMutation = repository.fetchMutatedReadingSessions().single()
        repository.updateReadingSession(original.local_id.toString(), 4, 20, timestamp(3000L))

        repository.applyRemoteChangesForMutations(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingSession(
                        chapterNumber = 3,
                        verseNumber = 10,
                        lastUpdated = timestamp(2000L)
                    ),
                    remoteID = "remote-reading-session-id",
                    mutation = Mutation.MODIFIED
                )
            ),
            localMutationsToClear = listOf(staleMutation)
        )

        val record = database.reading_sessionsQueries.getReadingSessionByRemoteId("remote-reading-session-id")
            .executeAsOne()
        val remaining = repository.fetchMutatedReadingSessions().single()
        assertEquals(4L, record.chapter_number)
        assertEquals(20L, record.verse_number)
        assertEquals(1L, record.is_edited)
        assertEquals(Mutation.MODIFIED, remaining.mutation)
    }

    @Test
    fun `stale created reading session ACK binds remote id and leaves newer move pending`() = runTest {
        val readingSession = repository.addReadingSession(2, 255, timestamp(1000L))
        val staleMutation = repository.fetchMutatedReadingSessions().single()
        repository.updateReadingSession(readingSession.localId, 3, 10, timestamp(2000L))

        repository.applyRemoteChangesForMutations(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingSession(
                        chapterNumber = 2,
                        verseNumber = 255,
                        lastUpdated = timestamp(1000L)
                    ),
                    remoteID = "remote-created-reading-session-id",
                    mutation = Mutation.CREATED,
                    ack = staleMutation.ack
                )
            ),
            localMutationsToClear = listOf(staleMutation)
        )

        val record = database.reading_sessionsQueries.getReadingSessionByLocalId(readingSession.localId.toLong())
            .executeAsOne()
        val remaining = repository.fetchMutatedReadingSessions().single()
        assertEquals("remote-created-reading-session-id", record.remote_id)
        assertEquals(3L, record.chapter_number)
        assertEquals(10L, record.verse_number)
        assertEquals(1L, record.is_edited)
        assertEquals(readingSession.localId, remaining.localID)
        assertEquals("remote-created-reading-session-id", remaining.remoteID)
        assertEquals(Mutation.MODIFIED, remaining.mutation)
    }

    @Test
    fun `stale created reading session ACK binds remote id and leaves delete pending`() = runTest {
        val readingSession = repository.addReadingSession(2, 255, timestamp(1000L))
        val staleMutation = repository.fetchMutatedReadingSessions().single()
        repository.deleteReadingSession(2, 255)
        assertEquals(emptyList(), repository.getReadingSessions())
        assertEquals(emptyList(), repository.fetchMutatedReadingSessions())

        repository.applyRemoteChangesForMutations(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingSession(
                        chapterNumber = 2,
                        verseNumber = 255,
                        lastUpdated = timestamp(1000L)
                    ),
                    remoteID = "remote-created-reading-session-id",
                    mutation = Mutation.CREATED,
                    ack = staleMutation.ack
                )
            ),
            localMutationsToClear = listOf(staleMutation)
        )

        val record = database.reading_sessionsQueries.getReadingSessionByLocalId(readingSession.localId.toLong())
            .executeAsOne()
        val remaining = repository.fetchMutatedReadingSessions().single()
        assertEquals(emptyList(), repository.getReadingSessions())
        assertEquals("remote-created-reading-session-id", record.remote_id)
        assertEquals(1L, record.deleted)
        assertEquals(readingSession.localId, remaining.localID)
        assertEquals("remote-created-reading-session-id", remaining.remoteID)
        assertEquals(Mutation.DELETED, remaining.mutation)
    }

    @Test
    fun `deleteExisting import keeps local-created reading session tombstone until create ACK binds`() = runTest {
        val readingSession = repository.addReadingSession(2, 255, timestamp(1000L))
        val staleMutation = repository.fetchMutatedReadingSessions().single()
        PersistenceImportRepositoryImpl(database).importData(
            data = PersistenceImportData(
                readingSessions = listOf(
                    ImportReadingSession(
                        sura = 2,
                        ayah = 255,
                        lastUpdated = timestamp(2000L)
                    )
                )
            ),
            deleteExisting = true
        )

        val tombstone = database.reading_sessionsQueries
            .getReadingSessionByLocalId(readingSession.localId.toLong())
            .executeAsOne()
        val imported = database.reading_sessionsQueries
            .getReadingSessionForChapterVerse(2L, 255L)
            .executeAsOne()
        assertEquals(null, tombstone.remote_id)
        assertEquals(1L, tombstone.deleted)
        assertEquals(readingSession.localId.toLong(), tombstone.local_id)
        assertEquals(1, repository.getReadingSessions().size)
        assertEquals(1, repository.fetchMutatedReadingSessions().size)
        assertEquals(0L, imported.deleted)
        assertEquals(null, imported.remote_id)
        assertEquals(2000L, imported.modified_at)

        repository.applyRemoteChangesForMutations(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingSession(
                        chapterNumber = 2,
                        verseNumber = 255,
                        lastUpdated = timestamp(1000L)
                    ),
                    remoteID = "remote-created-reading-session-id",
                    mutation = Mutation.CREATED,
                    ack = staleMutation.ack
                )
            ),
            localMutationsToClear = listOf(staleMutation)
        )

        val record = database.reading_sessionsQueries
            .getReadingSessionByLocalId(readingSession.localId.toLong())
            .executeAsOne()
        val remaining = repository.fetchMutatedReadingSessions()
        val tombstoneDelete = remaining.single { it.localID == readingSession.localId }
        val importedCreate = remaining.single { it.localID != readingSession.localId }
        assertEquals("remote-created-reading-session-id", record.remote_id)
        assertEquals(1L, record.deleted)
        assertEquals("remote-created-reading-session-id", tombstoneDelete.remoteID)
        assertEquals(Mutation.DELETED, tombstoneDelete.mutation)
        assertEquals(null, importedCreate.remoteID)
        assertEquals(Mutation.CREATED, importedCreate.mutation)
    }

    @Test
    fun `ACKed reading session delete removes tombstone without reactivating colliding active position`() = runTest {
        val readingSession = repository.addReadingSession(2, 255, timestamp(1000L))
        val staleCreate = repository.fetchMutatedReadingSessions().single()
        PersistenceImportRepositoryImpl(database).importData(
            data = PersistenceImportData(
                readingSessions = listOf(
                    ImportReadingSession(
                        sura = 2,
                        ayah = 255,
                        lastUpdated = timestamp(2000L)
                    )
                )
            ),
            deleteExisting = true
        )
        repository.applyRemoteChangesForMutations(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingSession(
                        chapterNumber = 2,
                        verseNumber = 255,
                        lastUpdated = timestamp(1000L)
                    ),
                    remoteID = "remote-created-reading-session-id",
                    mutation = Mutation.CREATED,
                    ack = staleCreate.ack
                )
            ),
            localMutationsToClear = listOf(staleCreate)
        )
        val staleDelete = repository.fetchMutatedReadingSessions()
            .single { it.localID == readingSession.localId }

        repository.applyRemoteChangesForMutations(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingSession(
                        chapterNumber = 2,
                        verseNumber = 255,
                        lastUpdated = timestamp(3000L)
                    ),
                    remoteID = "remote-created-reading-session-id",
                    mutation = Mutation.DELETED
                )
            ),
            localMutationsToClear = listOf(staleDelete)
        )

        val active = repository.getReadingSessions().single()
        val remainingMutation = repository.fetchMutatedReadingSessions().single()
        assertNull(
            database.reading_sessionsQueries
                .getReadingSessionByLocalId(readingSession.localId.toLong())
                .executeAsOneOrNull()
        )
        assertEquals(2, active.sura)
        assertEquals(255, active.ayah)
        assertEquals(Mutation.CREATED, remainingMutation.mutation)
        assertEquals(active.localId, remainingMutation.localID)
    }

    @Test
    fun `implicit prune retains local-created reading session until create ACK can bind`() = runTest {
        var now = 0L
        repository = ReadingSessionsRepositoryImpl(database) { now }
        lateinit var oldest: ReadingSession
        (1..20).forEach { ayah ->
            now = ayah * 1000L
            val readingSession = repository.addReadingSession(2, ayah)
            if (ayah == 1) {
                oldest = readingSession
            }
        }
        val staleMutation = repository.fetchMutatedReadingSessions()
            .single { it.localID == oldest.localId }

        now = 21_000L
        repository.addReadingSession(2, 21)
        assertTrue(repository.fetchMutatedReadingSessions().none { it.localID == oldest.localId })

        repository.applyRemoteChangesForMutations(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingSession(
                        chapterNumber = 2,
                        verseNumber = 1,
                        lastUpdated = timestamp(1000L)
                    ),
                    remoteID = "remote-pruned-reading-session-id",
                    mutation = Mutation.CREATED,
                    ack = staleMutation.ack
                )
            ),
            localMutationsToClear = listOf(staleMutation)
        )

        val record = database.reading_sessionsQueries.getReadingSessionByLocalId(oldest.localId.toLong())
            .executeAsOne()
        val remaining = repository.fetchMutatedReadingSessions()
            .single { it.localID == oldest.localId }
        assertEquals((21 downTo 2).toList(), repository.getReadingSessions().map { it.ayah })
        assertEquals("remote-pruned-reading-session-id", record.remote_id)
        assertEquals(1L, record.deleted)
        assertEquals("remote-pruned-reading-session-id", remaining.remoteID)
        assertEquals(Mutation.DELETED, remaining.mutation)
    }

    @Test
    fun `remote apply pruning tombstones local-created reading sessions without incrementing pending version`() = runTest {
        var now = 0L
        repository = ReadingSessionsRepositoryImpl(database) { now }
        lateinit var oldest: ReadingSession
        (1..20).forEach { ayah ->
            now = ayah * 1000L
            val readingSession = repository.addReadingSession(2, ayah, timestamp(now))
            if (ayah == 1) {
                oldest = readingSession
            }
        }
        val oldestCreate = repository.fetchMutatedReadingSessions()
            .single { it.localID == oldest.localId }
        val observedPendingVersion = oldestCreate.ack!!.observedPendingVersion

        repository.applyRemoteChangesForMutations(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingSession(
                        chapterNumber = 2,
                        verseNumber = 21,
                        lastUpdated = timestamp(21_000L)
                    ),
                    remoteID = "remote-reading-session-21",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val pruned = database.reading_sessionsQueries
            .getReadingSessionByLocalId(oldest.localId.toLong())
            .executeAsOne()
        assertEquals(1L, pruned.deleted)
        assertEquals(observedPendingVersion, pruned.pending_version)
        assertEquals((21 downTo 2).toList(), repository.getReadingSessions().map { it.ayah })
    }

    @Test
    fun `remote created reading session without ACK binds matching local-created row by position`() = runTest {
        val readingSession = repository.addReadingSession(2, 255, timestamp(1000L))

        repository.applyRemoteChangesForMutations(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingSession(
                        chapterNumber = 2,
                        verseNumber = 255,
                        lastUpdated = timestamp(1000L)
                    ),
                    remoteID = "remote-created-reading-session-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val localRecord = database.reading_sessionsQueries
            .getReadingSessionByLocalId(readingSession.localId.toLong())
            .executeAsOne()
        val readingSessions = repository.getReadingSessions()
        assertEquals("remote-created-reading-session-id", localRecord.remote_id)
        assertEquals(0L, localRecord.is_edited)
        assertEquals(1, readingSessions.size)
        assertEquals(readingSession.localId, readingSessions.single().localId)
        assertEquals(emptyList(), repository.fetchMutatedReadingSessions())
    }

    @Test
    fun `remote created reading session without ACK binds deleted pending create and leaves delete pending`() = runTest {
        val readingSession = repository.addReadingSession(2, 255, timestamp(1000L))
        repository.deleteReadingSession(2, 255)
        assertEquals(emptyList(), repository.getReadingSessions())
        assertEquals(emptyList(), repository.fetchMutatedReadingSessions())

        repository.applyRemoteChangesForMutations(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingSession(
                        chapterNumber = 2,
                        verseNumber = 255,
                        lastUpdated = timestamp(1000L)
                    ),
                    remoteID = "remote-created-reading-session-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val localRecord = database.reading_sessionsQueries
            .getReadingSessionByLocalId(readingSession.localId.toLong())
            .executeAsOne()
        val remaining = repository.fetchMutatedReadingSessions().single()
        assertEquals(emptyList(), repository.getReadingSessions())
        assertEquals("remote-created-reading-session-id", localRecord.remote_id)
        assertEquals(1L, localRecord.deleted)
        assertEquals(readingSession.localId, remaining.localID)
        assertEquals("remote-created-reading-session-id", remaining.remoteID)
        assertEquals(Mutation.DELETED, remaining.mutation)
    }

    @Test
    fun `remote created reading session replay binds deleted tombstone before active same-position create`() = runTest {
        val original = repository.addReadingSession(2, 255, timestamp(1000L))
        repository.deleteReadingSession(2, 255)
        val readded = repository.addReadingSession(2, 255, timestamp(2000L))

        repository.applyRemoteChangesForMutations(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingSession(
                        chapterNumber = 2,
                        verseNumber = 255,
                        lastUpdated = timestamp(1000L)
                    ),
                    remoteID = "remote-created-reading-session-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val tombstone = database.reading_sessionsQueries
            .getReadingSessionByLocalId(original.localId.toLong())
            .executeAsOne()
        val active = database.reading_sessionsQueries
            .getReadingSessionByLocalId(readded.localId.toLong())
            .executeAsOne()
        val visible = repository.getReadingSessions().single()
        val remaining = repository.fetchMutatedReadingSessions()
        val pendingDelete = remaining.single { it.localID == original.localId }
        val pendingCreate = remaining.single { it.localID == readded.localId }
        assertEquals("remote-created-reading-session-id", tombstone.remote_id)
        assertEquals(1L, tombstone.deleted)
        assertEquals(null, active.remote_id)
        assertEquals(0L, active.deleted)
        assertEquals(readded.localId, visible.localId)
        assertEquals("remote-created-reading-session-id", pendingDelete.remoteID)
        assertEquals(Mutation.DELETED, pendingDelete.mutation)
        assertEquals(null, pendingCreate.remoteID)
        assertEquals(Mutation.CREATED, pendingCreate.mutation)
    }

    @Test
    fun `remote created reading session replay rejects ambiguous deleted tombstones`() = runTest {
        val first = repository.addReadingSession(2, 255, timestamp(1000L))
        repository.deleteReadingSession(2, 255)
        val second = repository.addReadingSession(2, 255, timestamp(2000L))
        repository.deleteReadingSession(2, 255)
        assertEquals(emptyList(), repository.getReadingSessions())

        assertFailsWith<IllegalStateException> {
            repository.applyRemoteChangesForMutations(
                updatesToPersist = listOf(
                    RemoteModelMutation(
                        model = RemoteReadingSession(
                            chapterNumber = 2,
                            verseNumber = 255,
                            lastUpdated = timestamp(1000L)
                        ),
                        remoteID = "remote-created-reading-session-id",
                        mutation = Mutation.CREATED
                    )
                ),
                localMutationsToClear = emptyList()
            )
        }

        assertNull(
            database.reading_sessionsQueries
                .getReadingSessionByRemoteId("remote-created-reading-session-id")
                .executeAsOneOrNull()
        )
        val tombstones = database.reading_sessionsQueries
            .getDeletedPendingCreatedReadingSessionsForChapterVerse(2L, 255L)
            .executeAsList()
        assertEquals(listOf(first.localId.toLong(), second.localId.toLong()), tombstones.map { it.local_id })
        assertEquals(emptyList(), repository.getReadingSessions())
    }

    @Test
    fun `remote created reading session without ACK does not bind stale planned local create by position`() = runTest {
        val readingSession = repository.addReadingSession(2, 255, timestamp(1000L))
        val staleMutation = repository.fetchMutatedReadingSessions().single()
        repository.updateReadingSession(readingSession.localId, 3, 10, timestamp(2000L))

        repository.applyRemoteChangesForMutations(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingSession(
                        chapterNumber = 2,
                        verseNumber = 255,
                        lastUpdated = timestamp(1000L)
                    ),
                    remoteID = "remote-created-reading-session-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = listOf(staleMutation)
        )

        val localRecord = database.reading_sessionsQueries.getReadingSessionByLocalId(readingSession.localId.toLong())
            .executeAsOne()
        val remoteRecord = database.reading_sessionsQueries
            .getReadingSessionByRemoteId("remote-created-reading-session-id")
            .executeAsOne()
        val remaining = repository.fetchMutatedReadingSessions().single()
        assertEquals(null, localRecord.remote_id)
        assertEquals(3L, localRecord.chapter_number)
        assertEquals(10L, localRecord.verse_number)
        assertEquals(2L, remoteRecord.chapter_number)
        assertEquals(255L, remoteRecord.verse_number)
        assertEquals(readingSession.localId, remaining.localID)
        assertEquals(Mutation.CREATED, remaining.mutation)
    }

    @Test
    fun `remote created reading session without ACK does not move existing remote id by position`() = runTest {
        database.reading_sessionsQueries.persistRemoteReadingSession(
            remote_id = "remote-reading-session-1",
            chapter_number = 2L,
            verse_number = 255L,
            created_at = 1000L,
            modified_at = 1000L
        )

        repository.applyRemoteChangesForMutations(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingSession(
                        chapterNumber = 2,
                        verseNumber = 255,
                        lastUpdated = timestamp(2000L)
                    ),
                    remoteID = "remote-reading-session-2",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val original = database.reading_sessionsQueries
            .getReadingSessionByRemoteId("remote-reading-session-1")
            .executeAsOne()
        assertEquals(2L, original.chapter_number)
        assertEquals(255L, original.verse_number)
        assertNull(database.reading_sessionsQueries.getReadingSessionByRemoteId("remote-reading-session-2").executeAsOneOrNull())
    }

    private fun timestamp(milliseconds: Long) = Instant.fromEpochMilliseconds(milliseconds).toPlatform()
}
