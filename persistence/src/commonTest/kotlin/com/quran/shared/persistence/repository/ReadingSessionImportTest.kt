package com.quran.shared.persistence.repository

import app.cash.sqldelight.db.SqlDriver
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.input.ImportReadingSession
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.input.RemoteReadingSession
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepositoryImpl
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ReadingSessionImportTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: QuranDatabase
    private lateinit var repository: PersistenceImportRepositoryImpl
    private lateinit var readingSessions: ReadingSessionsRepositoryImpl

    @BeforeTest
    fun setup() {
        driver = TestDatabaseDriver().createDriver()
        database = QuranDatabase(driver)
        repository = PersistenceImportRepositoryImpl(database)
        readingSessions = ReadingSessionsRepositoryImpl(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `new position inserts a pending session with source dates`() = runTest {
        val result = repository.importData(sessionData(ImportReadingSession(2, 255, at(200), at(100))))

        val session = storedSession()
        assertEquals(1, result.readingSessionsImported)
        assertTrue(result.changed)
        assertEquals(100L, session.created_at)
        assertEquals(200L, session.modified_at)
        assertEquals(Mutation.CREATED, readingSessions.fetchMutatedReadingSessions().single().mutation)
    }

    @Test
    fun `equal visit time matches without changing the synced session`() = runTest {
        persistSyncedSession(modifiedAt = 200)
        val synced = storedSession()

        val result = repository.importData(sessionData(ImportReadingSession(2, 255, at(200))))

        assertEquals(1, result.matched)
        assertFalse(result.changed)
        assertEquals(synced, storedSession())
        assertTrue(readingSessions.fetchMutatedReadingSessions().isEmpty())
    }

    @Test
    fun `newer synced session is kept`() = runTest {
        persistSyncedSession(modifiedAt = 300)
        val synced = storedSession()

        val result = repository.importData(sessionData(ImportReadingSession(2, 255, at(200))))

        assertEquals(1, result.keptExisting)
        assertFalse(result.changed)
        assertEquals(synced, storedSession())
    }

    @Test
    fun `session with pending local changes is kept even when the visit is newer`() = runTest {
        readingSessions.addReadingSession(2, 255, at(100))
        val pending = storedSession()

        val result = repository.importData(sessionData(ImportReadingSession(2, 255, at(200))))

        assertEquals(1, result.keptExisting)
        assertFalse(result.changed)
        assertEquals(pending, storedSession())
    }

    @Test
    fun `older synced session advances while preserving identity and creation`() = runTest {
        persistSyncedSession(modifiedAt = 100, createdAt = 50)
        val synced = storedSession()

        val result = repository.importData(sessionData(ImportReadingSession(2, 255, at(200), at(10))))

        val advanced = storedSession()
        assertEquals(1, result.readingSessionsUpdated)
        assertEquals(0, result.readingSessionsImported)
        assertTrue(result.changed)
        assertEquals(synced.local_id, advanced.local_id)
        assertEquals(synced.remote_id, advanced.remote_id)
        assertEquals(50L, advanced.created_at)
        assertEquals(200L, advanced.modified_at)
        assertEquals(Mutation.MODIFIED, readingSessions.fetchMutatedReadingSessions().single().mutation)
    }

    @Test
    fun `visits at one position are processed newest first`() = runTest {
        val result = repository.importData(
            sessionData(
                ImportReadingSession(2, 255, at(200)),
                ImportReadingSession(2, 255, at(300))
            )
        )

        assertEquals(1, result.readingSessionsImported)
        assertEquals(1, result.keptExisting)
        assertEquals(300L, storedSession().modified_at)
    }

    @Test
    fun `tracked replay skips a handled visit and applies a new visit time`() = runTest {
        persistSyncedSession(modifiedAt = 100)
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

    private suspend fun persistSyncedSession(modifiedAt: Long, createdAt: Long? = null) {
        readingSessions.applyRemoteChangesForMutations(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingSession(
                        chapterNumber = 2,
                        verseNumber = 255,
                        lastUpdated = at(modifiedAt),
                        createdAt = createdAt?.let(::at)
                    ),
                    remoteID = "remote-session",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
    }

    private fun storedSession() =
        database.reading_sessionsQueries.getReadingSessionForChapterVerse(2, 255).executeAsOne()

    private fun sessionData(vararg sessions: ImportReadingSession) =
        PersistenceImportData(readingSessions = sessions.toList())

    private fun at(millis: Long) = Instant.fromEpochMilliseconds(millis).toPlatform()
}
