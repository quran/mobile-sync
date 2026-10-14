package com.quran.shared.persistence.repository

import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.at
import com.quran.shared.persistence.createTestDatabase
import com.quran.shared.persistence.input.ImportReadingSession
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepositoryImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadingSessionImportTest {
    private lateinit var database: MobileSyncDatabase
    private lateinit var repository: PersistenceImportRepositoryImpl
    private lateinit var readingSessions: ReadingSessionsRepositoryImpl

    @BeforeTest
    fun setup() {
        database = createTestDatabase()
        repository = PersistenceImportRepositoryImpl(database.quran, database.synced)
        readingSessions = ReadingSessionsRepositoryImpl(database.synced)
    }

    @Test
    fun `new position inserts a session with source dates`() = runTest {
        val result = repository.importData(sessionData(ImportReadingSession(2, 255, at(200), at(100))))

        val session = storedSession()
        assertEquals(1, result.readingSessionsImported)
        assertTrue(result.changed)
        assertEquals(100L, session.created_at)
        assertEquals(200L, session.updated_at)
    }

    @Test
    fun `equal visit time matches without changing the session`() = runTest {
        readingSessions.addReadingSession(2, 255, at(200))
        val existing = storedSession()

        val result = repository.importData(sessionData(ImportReadingSession(2, 255, at(200))))

        assertEquals(1, result.matched)
        assertFalse(result.changed)
        assertEquals(existing, storedSession())
    }

    @Test
    fun `newer session is kept`() = runTest {
        readingSessions.addReadingSession(2, 255, at(300))
        val existing = storedSession()

        val result = repository.importData(sessionData(ImportReadingSession(2, 255, at(200))))

        assertEquals(1, result.keptExisting)
        assertFalse(result.changed)
        assertEquals(existing, storedSession())
    }

    @Test
    fun `older session advances to a newer visit`() = runTest {
        readingSessions.addReadingSession(2, 255, at(100))
        val existing = storedSession()

        val result = repository.importData(sessionData(ImportReadingSession(2, 255, at(200))))

        val advanced = storedSession()
        assertEquals(1, result.readingSessionsUpdated)
        assertEquals(0, result.readingSessionsImported)
        assertTrue(result.changed)
        assertEquals(existing.local_id, advanced.local_id)
        assertEquals(200L, advanced.updated_at)
    }

    @Test
    fun `an advanced session keeps its creation date`() = runTest {
        database.synced.sessionsQueries.insert(2, 255, 50, 100)

        repository.importData(sessionData(ImportReadingSession(2, 255, at(200), at(10))))

        assertEquals(50L, storedSession().created_at)
    }

    @Test
    fun `tracked catch-up advances an imported session`() = runTest {
        repository.importData(sessionData(ImportReadingSession(18, 1, at(100))), deleteExisting = false, trackHistory = true)

        val catchUp = repository.importData(
            sessionData(ImportReadingSession(18, 1, at(200))),
            deleteExisting = false,
            trackHistory = true
        )

        assertEquals(1, catchUp.readingSessionsUpdated)
        assertEquals(200L, database.synced.sessionsQueries.at(18, 1).executeAsOne().updated_at)
    }

    @Test
    fun `visits at one position are processed newest first`() = runTest {
        val result = repository.importData(
            sessionData(ImportReadingSession(2, 255, at(200)), ImportReadingSession(2, 255, at(300)))
        )

        assertEquals(1, result.readingSessionsImported)
        assertEquals(1, result.keptExisting)
        assertEquals(300L, storedSession().updated_at)
    }

    @Test
    fun `tracked replay skips a handled visit and applies a new visit time`() = runTest {
        readingSessions.addReadingSession(2, 255, at(100))
        val handled = sessionData(ImportReadingSession(2, 255, at(200)))
        repository.importData(handled, deleteExisting = false, trackHistory = true)

        val replay = repository.importData(handled, deleteExisting = false, trackHistory = true)
        assertEquals(1, replay.alreadyProcessed)
        assertFalse(replay.changed)

        val newerVisit = repository.importData(
            sessionData(ImportReadingSession(3, 1, at(300))),
            deleteExisting = false,
            trackHistory = true
        )
        assertEquals(0, newerVisit.alreadyProcessed)
        assertEquals(1, newerVisit.readingSessionsImported)
    }

    @Test
    fun `importing twenty-five sessions keeps the newest twenty`() = runTest {
        val sessions = (1..25).map { ayah -> ImportReadingSession(2, ayah, at(ayah * 1000L)) }

        repository.importData(PersistenceImportData(readingSessions = sessions.shuffled()))

        assertEquals((25 downTo 6).toList(), readingSessions.getReadingSessions().map { it.ayah })
    }

    @Test
    fun `imported sessions and existing ones keep the newest twenty together`() = runTest {
        (1..15).forEach { ayah -> readingSessions.addReadingSession(3, ayah, at(ayah * 1000L)) }

        repository.importData(
            PersistenceImportData(readingSessions = (1..10).map { ayah -> ImportReadingSession(4, ayah, at(ayah * 1000L + 500L)) })
        )

        val remaining = readingSessions.getReadingSessions()
        assertEquals(20, remaining.size)
        assertEquals(
            ((1..15).map { Triple(3, it, it * 1000L) } + (1..10).map { Triple(4, it, it * 1000L + 500L) })
                .sortedByDescending { it.third }
                .take(20)
                .map { it.first to it.second },
            remaining.map { it.sura to it.ayah }
        )
    }

    private fun storedSession() = database.synced.sessionsQueries.at(2, 255).executeAsOne()

    private fun sessionData(vararg sessions: ImportReadingSession) = PersistenceImportData(readingSessions = sessions.toList())
}
