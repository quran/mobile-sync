package com.quran.shared.persistence.repository

import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.createTestDatabase
import com.quran.shared.persistence.repository.note.CreationTimes
import com.quran.shared.persistence.repository.note.NoteStore
import com.quran.shared.syncv2.types.NoteRange
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class CreationTimesTest {
    @Test
    fun `a free millisecond is kept`() {
        val times = CreationTimes(listOf(1_001L))

        assertEquals(1_000L, times.take(1_000L))
    }

    @Test
    fun `a taken millisecond moves to the first free one after the run`() {
        val times = CreationTimes(listOf(1_000L, 1_001L, 1_002L, 1_004L))

        assertEquals(listOf(1_003L, 1_005L, 1_006L, 1_007L), listOf(1_000L, 1_000L, 1_001L, 1_004L).map(times::take))
    }

    @Test
    fun `a batch gives every note the creation time a single insert would`() {
        val random = Random(42)
        val existing = List(200) { 1_000L + random.nextLong(400) }
        val asked = List(500) { 1_000L + random.nextLong(500) }
        val oneByOne = createTestDatabase()
        val batched = createTestDatabase()
        // NoteStore's callers write in a transaction.
        listOf(oneByOne, batched).forEach { database ->
            database.synced.transaction { existing.forEach { NoteStore(database.synced).insert("existing", RANGE, it) } }
        }

        oneByOne.synced.transaction {
            val single = NoteStore(oneByOne.synced)
            asked.forEach { single.insert("asked", RANGE, it) }
        }
        batched.synced.transaction {
            val batch = NoteStore(batched.synced).batch()
            asked.forEach { batch.insert("asked", RANGE, it) }
        }

        assertEquals(createdAts(oneByOne), createdAts(batched))
        assertEquals(existing.size + asked.size, createdAts(batched).toSet().size, "every note has a time of its own")
    }

    private fun createdAts(database: MobileSyncDatabase): List<Long> = database.synced.notesQueries.all().executeAsList().map { it.created_at }

    private companion object {
        val RANGE = listOf(NoteRange(2, 255, 2, 255))
    }
}
