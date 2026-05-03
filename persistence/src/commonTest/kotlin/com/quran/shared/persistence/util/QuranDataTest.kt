package com.quran.shared.persistence.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuranDataTest {
    @Test
    fun `getAyahIdOrNull maps valid sura and ayah to absolute ayah id`() {
        assertEquals(1, QuranData.getAyahIdOrNull(sura = 1, ayah = 1))
        assertEquals(7, QuranData.getAyahIdOrNull(sura = 1, ayah = 7))
        assertEquals(8, QuranData.getAyahIdOrNull(sura = 2, ayah = 1))
        assertEquals(20, QuranData.getAyahIdOrNull(sura = 2, ayah = 13))
        assertEquals(6236, QuranData.getAyahIdOrNull(sura = 114, ayah = 6))
    }

    @Test
    fun `getAyahIdOrNull returns null for invalid sura or ayah`() {
        assertNull(QuranData.getAyahIdOrNull(sura = 0, ayah = 1))
        assertNull(QuranData.getAyahIdOrNull(sura = 115, ayah = 1))
        assertNull(QuranData.getAyahIdOrNull(sura = 1, ayah = 0))
        assertNull(QuranData.getAyahIdOrNull(sura = 1, ayah = 8))
        assertNull(QuranData.getAyahIdOrNull(sura = 114, ayah = 7))
    }

    @Test
    fun `getSuraAyahOrNull maps valid absolute ayah id to sura and ayah`() {
        assertEquals(Pair(1, 1), QuranData.getSuraAyahOrNull(ayahId = 1))
        assertEquals(Pair(1, 7), QuranData.getSuraAyahOrNull(ayahId = 7))
        assertEquals(Pair(2, 1), QuranData.getSuraAyahOrNull(ayahId = 8))
        assertEquals(Pair(2, 13), QuranData.getSuraAyahOrNull(ayahId = 20))
        assertEquals(Pair(114, 6), QuranData.getSuraAyahOrNull(ayahId = 6236))
    }

    @Test
    fun `getSuraAyahOrNull returns null for invalid absolute ayah id`() {
        assertNull(QuranData.getSuraAyahOrNull(ayahId = 0))
        assertNull(QuranData.getSuraAyahOrNull(ayahId = -1))
        assertNull(QuranData.getSuraAyahOrNull(ayahId = 6237))
        assertNull(QuranData.getSuraAyahOrNull(ayahId = Int.MAX_VALUE.toLong() + 1))
    }
}
