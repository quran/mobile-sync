package com.quran.shared.persistence.repository

import com.quran.shared.mutations.Mutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.repository.note.repository.NotesRepositoryImpl
import com.quran.shared.persistence.util.QuranData
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class NotesRepositoryTest {
    private lateinit var database: QuranDatabase
    private lateinit var repository: NotesRepositoryImpl

    @BeforeTest
    fun setup() {
        database = QuranDatabase(TestDatabaseDriver().createDriver())
        repository = NotesRepositoryImpl(database)
    }

    @Test
    fun `addNote returns inserted note`() = runTest {
        val note = repository.addNote(
            body = "test note",
            startSura = 2,
            startAyah = 13,
            endSura = 2,
            endAyah = 13
        )

        assertEquals("test note", note.body)
        assertEquals(2, note.startSura)
        assertEquals(13, note.startAyah)
        assertEquals(2, note.endSura)
        assertEquals(13, note.endAyah)
    }

    @Test
    fun `addNote respects explicit timestamp`() = runTest {
        val note = repository.addNote(
            body = "test note",
            startSura = 2,
            startAyah = 13,
            endSura = 2,
            endAyah = 13,
            timestamp = timestamp(1_700_000_001_234L)
        )
        val record = database.notesQueries.getNoteByLocalId(note.localId.toLong()).executeAsOne()

        assertEquals(1_700_000_001_234L, note.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1_700_000_001_234L, record.created_at)
        assertEquals(1_700_000_001_234L, record.modified_at)
    }

    @Test
    fun `updateNote respects explicit timestamp and preserves created_at`() = runTest {
        val note = repository.addNote("test note", 2, 13, 2, 13, timestamp(1_700_000_001_000L))

        val updated = repository.updateNote(
            note.localId,
            "updated note",
            2,
            14,
            2,
            14,
            timestamp(1_700_000_002_345L)
        )
        val record = database.notesQueries.getNoteByLocalId(note.localId.toLong()).executeAsOne()

        assertEquals(1_700_000_002_345L, updated.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1_700_000_001_000L, record.created_at)
        assertEquals(1_700_000_002_345L, record.modified_at)
    }

    @Test
    fun `deleteNote preserves timestamp for remote rows`() = runTest {
        database.notesQueries.persistRemoteNote(
            remote_id = "remote-note-id",
            note = "test note",
            start_ayah_id = QuranData.getAyahId(2, 13).toLong(),
            end_ayah_id = QuranData.getAyahId(2, 14).toLong(),
            created_at = 1_700_000_001_000L,
            modified_at = 1_700_000_001_000L
        )
        val note = database.notesQueries.getNoteByLocalId(1L).executeAsOne()

        repository.deleteNote(note.local_id.toString())

        val mutation = repository.fetchMutatedNotes(0).single()
        val record = database.notesQueries.getNoteByLocalId(note.local_id).executeAsOne()
        assertEquals(Mutation.DELETED, mutation.mutation)
        assertEquals(1_700_000_001_000L, mutation.model.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1_700_000_001_000L, record.modified_at)
    }

    private fun timestamp(milliseconds: Long) = Instant.fromEpochMilliseconds(milliseconds).toPlatform()
}
