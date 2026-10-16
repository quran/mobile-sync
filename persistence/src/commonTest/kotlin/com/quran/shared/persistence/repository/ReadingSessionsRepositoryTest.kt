@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.quran.shared.persistence.repository

import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.RecordNotFoundException
import com.quran.shared.persistence.RecordNotFoundException.RecordType
import com.quran.shared.persistence.at
import com.quran.shared.persistence.createTestDatabase
import com.quran.shared.persistence.model.ReadingSession
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepositoryImpl
import com.quran.shared.persistence.util.fromPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
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

class ReadingSessionsRepositoryTest {
    private lateinit var database: MobileSyncDatabase
    private lateinit var repository: ReadingSessionsRepositoryImpl

    @BeforeTest
    fun setup() {
        database = createTestDatabase()
        repository = ReadingSessionsRepositoryImpl(database.synced)
    }

    @Test
    fun `addReadingSession respects explicit timestamp in milliseconds`() = runTest {
        val readingSession = repository.addReadingSession(2, 255, at(1234567890123L))
        val record = sessionAt(2, 255)

        assertEquals(1234567890123L, readingSession.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1234567890123L, record.created_at)
        assertEquals(1234567890123L, record.updated_at)
    }

    @Test
    fun `addReadingSession updates an existing session at the verse`() = runTest {
        val first = repository.addReadingSession(2, 255, at(1000L))

        val again = repository.addReadingSession(2, 255, at(1234567890123L))

        val record = sessionAt(2, 255)
        assertEquals(first.id, again.id)
        assertEquals(1234567890123L, again.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1000L, record.created_at)
        assertEquals(listOf(first.id), repository.getReadingSessions().map { it.id })
    }

    @Test
    fun `getReadingSessions lists the newest session first`() = runTest {
        repository.addReadingSession(2, 255, at(1000L))
        repository.addReadingSession(3, 10, at(3000L))
        repository.addReadingSession(1, 1, at(2000L))

        assertEquals(listOf(3 to 10, 1 to 1, 2 to 255), repository.getReadingSessions().map { it.sura to it.ayah })
    }

    @Test
    fun `deleteReadingSession removes the session at the verse`() = runTest {
        repository.addReadingSession(2, 255, at(1000L))
        repository.addReadingSession(3, 10, at(2000L))

        assertTrue(repository.deleteReadingSession(2, 255))

        assertNull(database.synced.sessionsQueries.at(2, 255).executeAsOneOrNull())
        assertEquals(listOf(3 to 10), repository.getReadingSessions().map { it.sura to it.ayah })
    }

    @Test
    fun `deleteReadingSession returns false when no session exists`() = runTest {
        repository.addReadingSession(2, 255, at(1000L))
        assertTrue(repository.deleteReadingSession(2, 255))

        assertFalse(repository.deleteReadingSession(2, 255), "already deleted")
        assertFalse(repository.deleteReadingSession(3, 10), "never added")
        assertEquals(emptyList(), repository.getReadingSessions())
    }

    @Test
    fun `updateReadingSession moves the session and preserves its id and created_at`() = runTest {
        val original = repository.addReadingSession(2, 255, at(1000L))

        val updated = repository.updateReadingSession(original.id, 3, 10, at(3000L))
        val record = database.synced.sessionsQueries.get(original.id.toLong()).executeAsOne()

        assertEquals(original.id, updated.id)
        assertEquals(1000L, record.created_at)
        assertEquals(3L, record.sura)
        assertEquals(10L, record.ayah)
    }

    @Test
    fun `updateReadingSession respects explicit timestamp`() = runTest {
        val readingSession = repository.addReadingSession(2, 255, at(1000L))

        val updated = repository.updateReadingSession(readingSession.id, 3, 10, at(3456L))
        val record = database.synced.sessionsQueries.get(readingSession.id.toLong()).executeAsOne()

        assertEquals(3456L, updated.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1000L, record.created_at)
        assertEquals(3456L, record.updated_at)
    }

    @Test
    fun `updateReadingSession merges source into existing target`() = runTest {
        val source = repository.addReadingSession(2, 255, at(1000L))
        val target = repository.addReadingSession(3, 10, at(2000L))

        val updated = repository.updateReadingSession(source.id, 3, 10, at(3000L))

        val targetRecord = database.synced.sessionsQueries.get(target.id.toLong()).executeAsOne()
        assertEquals(target.id, updated.id)
        assertEquals(3000L, updated.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertNull(database.synced.sessionsQueries.get(source.id.toLong()).executeAsOneOrNull())
        assertEquals(2000L, targetRecord.created_at)
        assertEquals(3000L, targetRecord.updated_at)
        assertEquals(listOf(target.id), repository.getReadingSessions().map { it.id })
    }

    @Test
    fun `updateReadingSession rejects an id that names no session`() = runTest {
        val deleted = repository.addReadingSession(2, 255, at(1000L))
        assertTrue(repository.deleteReadingSession(2, 255))

        listOf(deleted.id, "999", "not-a-number").forEach { id ->
            val failure = assertFailsWith<RecordNotFoundException> { repository.updateReadingSession(id, 3, 10, at(2000L)) }
            assertEquals(RecordType.READING_SESSION, failure.type)
            assertEquals(id, failure.id)
        }
        assertEquals(emptyList(), repository.getReadingSessions(), "the deleted session is not resurrected")
    }

    @Test
    fun `addReadingSession keeps the newest twenty sessions`() = runTest {
        (1..21).forEach { ayah -> repository.addReadingSession(2, ayah, at(ayah * 1000L)) }

        val remaining = repository.getReadingSessions()

        assertEquals((21 downTo 2).toList(), remaining.map { it.ayah })
        assertNull(database.synced.sessionsQueries.at(2, 1).executeAsOneOrNull())
    }

    @Test
    fun `sessions visited at the same time are kept by verse`() = runTest {
        (21 downTo 1).forEach { ayah -> repository.addReadingSession(2, ayah, at(1000L)) }

        assertEquals((1..20).toList(), repository.getReadingSessions().map { it.ayah })
    }

    @Test
    fun `revisiting a verse keeps it among the newest twenty`() = runTest {
        (1..20).forEach { ayah -> repository.addReadingSession(2, ayah, at(ayah * 1000L)) }

        repository.addReadingSession(2, 1, at(21_000L))
        repository.addReadingSession(2, 21, at(22_000L))

        assertEquals(listOf(21, 1) + (20 downTo 3).toList(), repository.getReadingSessions().map { it.ayah })
    }

    @Test
    fun `a visit older than the newest twenty leaves them as they are`() = runTest {
        (1..20).forEach { ayah -> repository.addReadingSession(2, ayah, at(ayah * 1000L + 1000L)) }

        repository.addReadingSession(3, 1, at(1000L))

        assertEquals((20 downTo 1).toList(), repository.getReadingSessions().map { it.ayah })
    }

    @Test
    fun `reading sessions flow emits final state after update and implicit prune`() = runTest {
        lateinit var oldest: ReadingSession
        (1..20).forEach { ayah ->
            val session = repository.addReadingSession(2, ayah, at(ayah * 1000L))
            if (ayah == 1) oldest = session
        }

        val emissions = Channel<List<ReadingSession>>(Channel.UNLIMITED)
        val job = launch { repository.getReadingSessionsFlow().collect { emissions.send(it) } }
        runCurrent()

        suspend fun receiveEmission(): List<ReadingSession> =
            withContext(Dispatchers.Default) { withTimeout(5000) { emissions.receive() } }

        try {
            assertEquals((20 downTo 1).toList(), receiveEmission().map { it.ayah })

            repository.updateReadingSession(oldest.id, 2, 21, at(21_000L))
            assertEquals((21 downTo 2).toList(), receiveEmission().map { it.ayah })

            repository.addReadingSession(2, 22, at(22_000L))
            assertEquals((22 downTo 3).toList(), receiveEmission().map { it.ayah })
        } finally {
            job.cancelAndJoin()
            emissions.close()
        }
    }

    private fun sessionAt(sura: Long, ayah: Long) = database.synced.sessionsQueries.at(sura, ayah).executeAsOne()
}
