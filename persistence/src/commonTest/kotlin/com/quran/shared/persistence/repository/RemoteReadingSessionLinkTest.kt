package com.quran.shared.persistence.repository

import app.cash.sqldelight.db.SqlDriver
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.createQuranDatabase
import com.quran.shared.persistence.input.RemoteReadingSession
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepositoryImpl
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class RemoteReadingSessionLinkTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: QuranDatabase
    private lateinit var repository: ReadingSessionsRepositoryImpl

    @BeforeTest
    fun setup() {
        driver = TestDatabaseDriver().createDriver()
        database = createQuranDatabase(driver)
        repository = ReadingSessionsRepositoryImpl(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `newer remote update links the pending local session at the same verse`() = runTest {
        val local = repository.addReadingSession(112, 1, at(1_000L))
        val pending = repository.fetchMutatedReadingSessions()

        repository.applyRemoteChangesForMutations(
            updatesToPersist = listOf(remoteUpdate(112, 1, "remote-112-1", updatedAt = 2_000L)),
            localMutationsToClear = pending
        )

        val record = database.reading_sessionsQueries.getReadingSessionByLocalId(local.id.toLong()).executeAsOne()
        assertEquals("remote-112-1", record.remote_id)
        assertEquals(2_000L, record.modified_at)
        assertEquals(0L, record.is_edited)
        assertEquals(listOf(local.id), repository.getReadingSessions().map { it.id })
        assertEquals(emptyList(), repository.fetchMutatedReadingSessions())
    }

    @Test
    fun `older remote create leaves a newer pending local session to be pushed`() = runTest {
        val local = repository.addReadingSession(1, 1, at(3_000L))

        repository.applyRemoteChangesForMutations(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingSession(chapterNumber = 1, verseNumber = 1, lastUpdated = at(2_000L)),
                    remoteID = "remote-1-1",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val pending = repository.fetchMutatedReadingSessions().single()
        assertEquals(local.id, pending.localID)
        assertEquals(Mutation.CREATED, pending.mutation)
        assertEquals(3_000L, database.reading_sessionsQueries.getReadingSessionByLocalId(local.id.toLong()).executeAsOne().modified_at)
        assertNull(database.reading_sessionsQueries.getReadingSessionByRemoteId("remote-1-1").executeAsOneOrNull())
    }

    @Test
    fun `remote update does not link a deleted never-synced session at the same verse`() = runTest {
        val local = repository.addReadingSession(112, 1, at(1_000L))
        repository.deleteReadingSession(112, 1)

        repository.applyRemoteChangesForMutations(
            updatesToPersist = listOf(remoteUpdate(112, 1, "remote-112-1", updatedAt = 2_000L)),
            localMutationsToClear = emptyList()
        )

        assertNull(database.reading_sessionsQueries.getReadingSessionByLocalId(local.id.toLong()).executeAsOne().remote_id)
        val remote = database.reading_sessionsQueries.getReadingSessionByRemoteId("remote-112-1").executeAsOne()
        assertEquals(0L, remote.deleted)
        assertEquals(2_000L, remote.modified_at)
    }

    private fun remoteUpdate(sura: Int, ayah: Int, remoteId: String, updatedAt: Long) = RemoteModelMutation(
        model = RemoteReadingSession(chapterNumber = sura, verseNumber = ayah, lastUpdated = at(updatedAt)),
        remoteID = remoteId,
        mutation = Mutation.MODIFIED
    )

    private fun at(milliseconds: Long) = Instant.fromEpochMilliseconds(milliseconds).toPlatform()
}
