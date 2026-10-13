package com.quran.shared.mutations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerTextLimitTest {

    @Test
    fun `note body limits are measured on the trimmed escaped text`() {
        val limit = ServerTextLimit.NOTE_BODY

        assertFalse(limit.accepts("a".repeat(5)))
        assertTrue(limit.accepts("a".repeat(6)))
        assertTrue(limit.accepts("a".repeat(10_000)))
        assertFalse(limit.accepts("a".repeat(10_001)))
        assertFalse(limit.accepts("  Good \n"))
        assertTrue(limit.accepts("a&b"))
        assertEquals(10_001, limit.serverLength("a".repeat(9_996) + "&"))
        assertFalse(limit.accepts("a".repeat(9_996) + "&"))
        assertTrue(limit.accepts("a".repeat(9_995) + "&"))
    }

    @Test
    fun `collection name limits are measured on the trimmed escaped text`() {
        val limit = ServerTextLimit.COLLECTION_NAME

        assertFalse(limit.accepts(""))
        assertFalse(limit.accepts(" \u3000\uFEFF"))
        assertTrue(limit.accepts("a"))
        assertTrue(limit.accepts("a".repeat(255)))
        assertFalse(limit.accepts("a".repeat(256)))
        assertFalse(limit.accepts("a".repeat(252) + "<"))
        assertTrue(limit.accepts("a".repeat(251) + "<"))
    }

    @Test
    fun `lengths count UTF-16 code units like JavaScript`() {
        assertEquals(2, ServerTextLimit.COLLECTION_NAME.serverLength("🤍"))
    }
}
