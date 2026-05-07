@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.persistence.repository

import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.input.RemoteReadingSession
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepositoryImpl
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `addReadingSession updates existing session with latest millisecond timestamp`() = runTest {
        var now = 1000L
        repository = ReadingSessionsRepositoryImpl(database) { now }

        repository.addReadingSession(2, 255)
        now = 1234567890123L
        val readingSession = repository.addReadingSession(2, 255)

        assertEquals(1234567890123L, readingSession.lastUpdated.fromPlatform().toEpochMilliseconds())
    }

    @Test
    fun `deleteReadingSession persists millisecond timestamp for remote rows`() = runTest {
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
        assertEquals(Mutation.DELETED, localMutation.mutation)
        assertEquals(1234567890123L, localMutation.model.lastUpdated.fromPlatform().toEpochMilliseconds())
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
}
