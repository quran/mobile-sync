package com.quran.shared.persistence.repository

import app.cash.sqldelight.db.SqlDriver
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.createQuranDatabase
import com.quran.shared.persistence.input.RemoteNote
import com.quran.shared.persistence.repository.note.repository.NotesRepositoryImpl
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class NoteBodyTrimTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: QuranDatabase
    private lateinit var repository: NotesRepositoryImpl

    @BeforeTest
    fun setup() {
        driver = TestDatabaseDriver().createDriver()
        database = createQuranDatabase(driver)
        repository = NotesRepositoryImpl(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `server echo of a note typed with trailing whitespace links its pending create`() = runTest {
        val note = repository.addNote("Reflect on this verse \n", 2, 255, 2, 255, at(1_000L))

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteNote(
                        body = "Reflect on this verse",
                        startSura = 2,
                        startAyah = 255,
                        endSura = 2,
                        endAyah = 255,
                        lastUpdated = at(1_000L)
                    ),
                    remoteID = "remote-note",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        assertEquals("remote-note", database.notesQueries.getNoteByLocalId(note.id.toLong()).executeAsOne().remote_id)
        assertEquals(listOf(note.id), repository.getAllNotes().map { it.id })
        assertEquals(emptyList(), repository.fetchMutatedNotes(0))
    }

    @Test
    fun `note bodies are saved trimmed the way the server trims them`() = runTest {
        val added = repository.addNote("\uFEFF  Reflect on it\n\u3000", 2, 255, 2, 255, at(1_000L))
        val updated = repository.addNote("first note", 3, 1, 3, 1, at(1_000L))
            .let { repository.updateNote(it.id, "\u001Ckept note\u001C \n", 3, 1, 3, 1, at(2_000L)) }

        assertEquals("Reflect on it", added.body)
        assertEquals("\u001Ckept note\u001C", updated.body)
    }

    private fun at(milliseconds: Long) = Instant.fromEpochMilliseconds(milliseconds).toPlatform()
}
