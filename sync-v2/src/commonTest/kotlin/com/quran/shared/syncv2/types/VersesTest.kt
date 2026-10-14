package com.quran.shared.syncv2.types

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersesTest {
    @Test
    fun `the verse table covers the 6236 verses`() {
        assertEquals(6236, (1..114).sumOf { sura -> (1..286).count { Verses.exists(sura, it) } })
        assertTrue(Verses.exists(114, 6))
        assertFalse(Verses.exists(1, 8))
        assertFalse(Verses.exists(115, 1))
    }
}
