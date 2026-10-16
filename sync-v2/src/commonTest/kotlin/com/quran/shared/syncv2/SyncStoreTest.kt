package com.quran.shared.syncv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SyncStoreTest {
    private val database = inMemoryDatabase()
    private val store = SyncStore(database)

    @Test
    fun `a new tombstone id is below every sync row and every domain row`() {
        assertEquals(-1, store.newTombstoneId("T", emptySet()))
        store.put("T", SyncRow(-1, "r1", stateOf()))
        assertEquals(-2, store.newTombstoneId("T", setOf(5)))
        // A domain row kept -3 after its sync row went away (restored, then edited and recreated).
        assertEquals(-4, store.newTombstoneId("T", setOf(-3, 7)))
    }

    /** The parser's error quotes the text it read, and an error can reach the logs. */
    @Test
    fun `stored JSON that doesn't parse fails without quoting it`() {
        database.sync_itemQueries.upsert("NOTE", 1, null, null, "BODY_LENGTH", "{\"body\": \"$SECRET", null)
        database.sync_itemQueries.metaSet("outbox", "[{\"account\": \"$SECRET")

        listOf(
            assertFailsWith<IllegalStateException> { store.row("NOTE", 1) },
            assertFailsWith<IllegalStateException> { store.outboxes }
        ).forEach { error ->
            assertEquals(false, SECRET in error.toString(), error.toString())
            assertNull(error.cause, "the parser's error would quote it too")
        }
    }

    @Test
    fun `a missing field names the state's fields, not its values`() {
        val body = Field.string("body")

        val error = assertFailsWith<IllegalStateException> { stateOf(body(SECRET))[Field.int("sura")] }

        assertEquals("sura missing in a state with [body]", error.message)
    }

    private companion object {
        const val SECRET = "A private reflection on mercy"
    }
}
