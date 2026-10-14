package com.quran.shared.syncv2

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncStoreTest {
    private val store = SyncStore(inMemoryDatabase())

    @Test
    fun `a new tombstone id is below every sync row and every domain row`() {
        assertEquals(-1, store.newTombstoneId("T", emptySet()))
        store.put("T", SyncRow(-1, "r1", stateOf()))
        assertEquals(-2, store.newTombstoneId("T", setOf(5)))
        // A domain row kept -3 after its sync row went away (restored, then edited and recreated).
        assertEquals(-4, store.newTombstoneId("T", setOf(-3, 7)))
    }
}
