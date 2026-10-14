package com.quran.shared.persistence.repository

import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.TextLengthBound
import com.quran.shared.persistence.TextLengthField
import com.quran.shared.persistence.TextLengthLimitException
import com.quran.shared.persistence.at
import com.quran.shared.persistence.createTestDatabase
import com.quran.shared.persistence.input.ImportNote
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.repository.note.repository.NotesRepositoryImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TextLengthLimitTest {
    private lateinit var database: MobileSyncDatabase
    private lateinit var notes: NotesRepositoryImpl
    private lateinit var collections: CollectionsRepositoryImpl

    @BeforeTest
    fun setup() {
        database = createTestDatabase()
        notes = NotesRepositoryImpl(database.synced)
        collections = CollectionsRepositoryImpl(database.synced)
    }

    @Test
    fun `note bodies must be 6 to 10000 characters as the server measures them`() = runTest {
        assertLimit(TextLengthField.NOTE_BODY, TextLengthBound.TOO_SHORT, limit = 6, length = 5) { addNote("a".repeat(5)) }
        addNote("a".repeat(6))
        addNote("a".repeat(10_000))
        assertLimit(TextLengthField.NOTE_BODY, TextLengthBound.TOO_LONG, limit = 10_000, length = 10_001) {
            addNote("a".repeat(10_001))
        }
        assertLimit(TextLengthField.NOTE_BODY, TextLengthBound.TOO_SHORT, limit = 6, length = 4) { addNote("  Good \n") }
        addNote("a".repeat(9_995) + "&")
        assertLimit(TextLengthField.NOTE_BODY, TextLengthBound.TOO_LONG, limit = 10_000, length = 10_001) {
            addNote("a".repeat(9_996) + "&")
        }
    }

    @Test
    fun `note update outside the limits is rejected and leaves the note unchanged`() = runTest {
        val note = addNote("Reflect on this verse")

        assertLimit(TextLengthField.NOTE_BODY, TextLengthBound.TOO_SHORT, limit = 6, length = 4) {
            notes.updateNote(note.id, "Good", at(2_000L))
        }

        assertEquals(listOf("Reflect on this verse"), notes.getAllNotes().map { it.body })
    }

    @Test
    fun `a legacy short note can still be saved with its body unchanged`() = runTest {
        PersistenceImportRepositoryImpl(database.quran, database.synced).importData(
            PersistenceImportData(notes = listOf(ImportNote("Good", 2, 255, 2, 255, at(1_000L))))
        )
        val legacy = notes.getAllNotes().single()

        val updated = notes.updateNote(legacy.id, " Good\n", at(2_000L))

        assertEquals("Good", updated.body)
        assertLimit(TextLengthField.NOTE_BODY, TextLengthBound.TOO_SHORT, limit = 6, length = 4) {
            notes.updateNote(legacy.id, "Fine", at(3_000L))
        }
    }

    @Test
    fun `collection names must be 1 to 255 characters as the server measures them`() = runTest {
        assertLimit(TextLengthField.COLLECTION_NAME, TextLengthBound.TOO_SHORT, limit = 1, length = 0) {
            collections.addCollection(" 　", at(1_000L))
        }
        collections.addCollection("a", at(1_000L))
        collections.addCollection("b".repeat(255), at(1_000L))
        assertLimit(TextLengthField.COLLECTION_NAME, TextLengthBound.TOO_LONG, limit = 255, length = 256) {
            collections.addCollection("c".repeat(256), at(1_000L))
        }
        collections.addCollection("d".repeat(251) + "<", at(1_000L))
        assertLimit(TextLengthField.COLLECTION_NAME, TextLengthBound.TOO_LONG, limit = 255, length = 256) {
            collections.addCollection("e".repeat(252) + "<", at(1_000L))
        }
    }

    @Test
    fun `collection rename outside the limits is rejected and keeps the name`() = runTest {
        val study = collections.addCollection("Study", at(1_000L))

        assertLimit(TextLengthField.COLLECTION_NAME, TextLengthBound.TOO_LONG, limit = 255, length = 256) {
            collections.updateCollection(study.id, "a".repeat(251) + "&", at(2_000L))
        }

        assertEquals("Study", collections.getAllCollections().single { it.id == study.id }.name)
    }

    private suspend fun addNote(body: String) = notes.addNote(body, 2, 255, 2, 255, at(1_000L))

    private suspend fun assertLimit(
        field: TextLengthField,
        bound: TextLengthBound,
        limit: Int,
        length: Int,
        block: suspend () -> Unit
    ) {
        val error = assertIs<TextLengthLimitException>(runCatching { block() }.exceptionOrNull())
        assertEquals(field, error.field)
        assertEquals(bound, error.bound)
        assertEquals(limit, error.limit)
        assertEquals(length, error.length)
    }
}
