package com.quran.shared.persistence.repository

import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.repository.note.repository.NotesRepositoryImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
            startAyahId = 20L,
            endAyahId = 20L
        )

        assertEquals("test note", note.body)
        assertEquals(20L, note.startAyahId)
        assertEquals(20L, note.endAyahId)
    }
}
