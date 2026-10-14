package com.quran.shared.persistence.repository

import com.quran.shared.persistence.at
import com.quran.shared.persistence.createTestDatabase
import com.quran.shared.persistence.input.ImportNote
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/** Imports of a large legacy data set. JVM only: the bound is wall-clock time. */
class ImportScaleTest {
    @Test
    fun `an import of many notes sharing one creation time takes linear time`() {
        val database = createTestDatabase()
        val repository = PersistenceImportRepositoryImpl(database.quran, database.synced)
        val notes = (0 until NOTES).map { ImportNote("Imported note $it", 2, 1 + it % 286, 2, 1 + it % 286, at(100), at(100)) }

        // Linear, this takes well under a second; nudging each note with a query that rescans the taken run took
        // about 15 s.
        val elapsed = measureTime { runBlocking { repository.importData(PersistenceImportData(notes = notes)) } }

        assertTrue(elapsed < 5.seconds, "the import took $elapsed")
        assertEquals((100L until 100L + NOTES).toList(), database.synced.notesQueries.all().executeAsList().map { it.created_at }.sorted())
    }

    private companion object {
        const val NOTES = 20_000
    }
}
