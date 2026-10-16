package com.quran.shared.persistence.repository

import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.at
import com.quran.shared.persistence.createTestDatabase
import com.quran.shared.persistence.model.NoteRange as PublicNoteRange
import com.quran.shared.persistence.repository.note.NoteStore
import com.quran.shared.persistence.repository.note.repository.NotesRepositoryImpl
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.syncv2.types.NoteRange
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotesRepositoryTest {
    private lateinit var database: MobileSyncDatabase
    private lateinit var repository: NotesRepositoryImpl

    @BeforeTest
    fun setup() {
        database = createTestDatabase()
        repository = NotesRepositoryImpl(database.synced)
    }

    @Test
    fun `addNote returns inserted note`() = runTest {
        val note = repository.addNote(body = "test note", startSura = 2, startAyah = 13, endSura = 2, endAyah = 13)

        assertEquals("test note", note.body)
        assertEquals(listOf(PublicNoteRange(2, 13, 2, 13)), note.ranges)
        assertEquals(listOf(note), repository.getAllNotes())
    }

    @Test
    fun `addNote preserves coordinates without applying an ayah counting method`() = runTest {
        val note = repository.addNote(body = "alternate counting note", startSura = 1, startAyah = 8, endSura = 1, endAyah = 8)

        assertEquals(listOf(PublicNoteRange(1, 8, 1, 8)), note.ranges)
    }

    @Test
    fun `addNote respects explicit timestamp`() = runTest {
        val note = repository.addNote("test note", 2, 13, 2, 13, at(1_700_000_001_234L))
        val record = noteRow(note.id)

        assertEquals(1_700_000_001_234L, note.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1_700_000_001_234L, record.created_at)
        assertEquals(1_700_000_001_234L, record.updated_at)
        assertNull(record.archived_at)
    }

    @Test
    fun `a note created at a taken millisecond is moved to the next free one`() = runTest {
        val first = repository.addNote("first note", 2, 13, 2, 13, at(1_000L))
        val second = repository.addNote("second note", 2, 13, 2, 13, at(1_000L))
        val third = repository.addNote("third note", 3, 1, 3, 1, at(1_000L))

        assertEquals(listOf(1_000L, 1_001L, 1_002L), listOf(first, second, third).map { noteRow(it.id).created_at })
        assertEquals(1_001L, second.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1_001L, noteRow(second.id).updated_at)
    }

    @Test
    fun `a note created at a free millisecond keeps it when the next one is taken`() = runTest {
        repository.addNote("later note", 2, 13, 2, 13, at(1_001L))

        val note = repository.addNote("earlier note", 2, 13, 2, 13, at(1_000L))

        assertEquals(1_000L, noteRow(note.id).created_at)
    }

    @Test
    fun `a moved creation time skips every taken millisecond after it`() = runTest {
        repository.addNote("note at 1000", 2, 13, 2, 13, at(1_000L))
        repository.addNote("note at 1001", 2, 13, 2, 13, at(1_001L))
        repository.addNote("note at 1003", 2, 13, 2, 13, at(1_003L))

        val note = repository.addNote("another note", 2, 13, 2, 13, at(1_000L))

        assertEquals(1_002L, noteRow(note.id).created_at)
    }

    @Test
    fun `updateNote respects explicit timestamp and preserves created_at`() = runTest {
        val note = repository.addNote("test note", 2, 13, 2, 13, at(1_700_000_001_000L))

        val updated = repository.updateNote(note.id, "updated note", at(1_700_000_002_345L))
        val record = noteRow(note.id)

        assertEquals("updated note", updated.body)
        assertEquals(1_700_000_002_345L, updated.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1_700_000_001_000L, record.created_at)
        assertEquals(1_700_000_002_345L, record.updated_at)
    }

    @Test
    fun `updateNote keeps the note's verse range`() = runTest {
        val note = repository.addNote("test note", 2, 13, 2, 14, at(1_000L))

        val updated = repository.updateNote(note.id, "updated note", at(2_000L))

        assertEquals(listOf(PublicNoteRange(2, 13, 2, 14)), updated.ranges)
        assertEquals(listOf(NoteRange(2, 13, 2, 14)), ranges(note.id))
    }

    @Test
    fun `updateNote keeps every range of a note with several`() = runTest {
        val id = insertNote("web note", listOf(NoteRange(2, 1, 2, 3), NoteRange(3, 5, 3, 7)), createdAt = 1_000L)

        repository.updateNote(id.toString(), "edited web note", at(2_000L))

        assertEquals(listOf(NoteRange(2, 1, 2, 3), NoteRange(3, 5, 3, 7)), ranges(id.toString()))
    }

    @Test
    fun `updateNote rejects an id that names no note`() = runTest {
        assertFailsWith<IllegalArgumentException> { repository.updateNote("999", "updated note", at(2_000L)) }
        assertFailsWith<IllegalArgumentException> { repository.updateNote("not-a-number", "updated note", at(2_000L)) }
    }

    @Test
    fun `a note with several ranges shows every range in order`() = runTest {
        insertNote("web note", listOf(NoteRange(3, 5, 3, 7), NoteRange(2, 1, 2, 3)), createdAt = 1_000L)

        val note = repository.getAllNotes().single()

        assertEquals(listOf(PublicNoteRange(3, 5, 3, 7), PublicNoteRange(2, 1, 2, 3)), note.ranges)
    }

    @Test
    fun `getAllNotes lists the newest note first`() = runTest {
        repository.addNote("older note", 2, 13, 2, 13, at(1_000L))
        repository.addNote("newer note", 2, 14, 2, 14, at(2_000L))

        assertEquals(listOf("newer note", "older note"), repository.getAllNotes().map { it.body })
    }

    @Test
    fun `getAllNotes and getNotesFlow hide archived notes`() = runTest {
        val archived = repository.addNote("archived note", 2, 13, 2, 13, at(1_000L))
        val active = repository.addNote("active note", 2, 14, 2, 14, at(2_000L))

        archive(archived.id, at = 3_000L)

        assertEquals(listOf(active.id), repository.getAllNotes().map { it.id })
        assertEquals(listOf(active.id), repository.getNotesFlow().first().map { it.id })
    }

    @Test
    fun `deleteNote returns true when an active note is deleted`() = runTest {
        val note = repository.addNote(body = "a note", startSura = 2, startAyah = 13, endSura = 2, endAyah = 13)

        val deleted = repository.deleteNote(note.id)

        assertTrue(deleted)
        assertNull(database.synced.notesQueries.get(note.id.toLong()).executeAsOneOrNull())
        assertEquals(emptyList(), ranges(note.id))
        assertTrue(repository.getAllNotes().isEmpty())
    }

    @Test
    fun `deleteNote returns false for a missing note without writing`() = runTest {
        val note = repository.addNote(body = "a note", startSura = 2, startAyah = 13, endSura = 2, endAyah = 13)
        val before = noteRow(note.id)

        val deleted = repository.deleteNote("999")

        assertFalse(deleted)
        assertEquals(listOf(before), database.synced.notesQueries.all().executeAsList())
    }

    @Test
    fun `deleteNote returns false when the note is already deleted`() = runTest {
        val note = repository.addNote(body = "a note", startSura = 2, startAyah = 13, endSura = 2, endAyah = 13)
        assertTrue(repository.deleteNote(note.id))

        val deletedAgain = repository.deleteNote(note.id)

        assertFalse(deletedAgain)
        assertEquals(emptyList(), database.synced.notesQueries.all().executeAsList())
    }

    private fun noteRow(id: String) = database.synced.notesQueries.get(id.toLong()).executeAsOne()

    private fun ranges(id: String) = database.synced.notesQueries.rangesOf(id.toLong()).executeAsList().map {
        NoteRange(it.start_sura.toInt(), it.start_ayah.toInt(), it.end_sura.toInt(), it.end_ayah.toInt())
    }

    private fun insertNote(body: String, ranges: List<NoteRange>, createdAt: Long): Long =
        database.synced.transactionWithResult { NoteStore(database.synced).insert(body, ranges, createdAt) }

    private fun archive(id: String, at: Long) {
        val row = noteRow(id)
        database.synced.notesQueries.update(row.body, row.created_at, row.updated_at, at, row.local_id)
    }
}
