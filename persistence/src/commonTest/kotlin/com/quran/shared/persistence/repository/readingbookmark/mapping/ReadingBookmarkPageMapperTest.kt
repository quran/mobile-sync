package com.quran.shared.persistence.repository.readingbookmark.mapping

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadingBookmarkPageMapperTest {

    @Test
    fun `a page maps to the page holding its middle verse`() {
        assertEquals(371, map(334, PageLayout.INDOPAK_16_LINES, PageLayout.MADANI_1440))
        assertEquals(595, map(540, PageLayout.INDOPAK_16_LINES, PageLayout.MADANI_1440))
        assertEquals(536, map(536, PageLayout.MADANI_1440, PageLayout.INDOPAK_15_LINES))
        assertEquals(523, map(523, PageLayout.INDOPAK_15_LINES, PageLayout.MADANI_1440))
    }

    @Test
    fun `a candidate whose middle verse runs across the source page break is kept`() {
        assertEquals(47, map(43, PageLayout.INDOPAK_16_LINES, PageLayout.MADANI_1440))
    }

    @Test
    fun `a mapped page maps back`() {
        val madani = map(301, PageLayout.INDOPAK_16_LINES, PageLayout.MADANI_1440)

        assertEquals(301, map(madani, PageLayout.MADANI_1440, PageLayout.INDOPAK_16_LINES))
    }

    @Test
    fun `a Shemerly page maps to the 1440 page with its verses and back`() {
        assertEquals(302, map(250, PageLayout.SHEMERLY, PageLayout.MADANI_1440))
        assertEquals(250, map(302, PageLayout.MADANI_1440, PageLayout.SHEMERLY))
        assertEquals(604, map(521, PageLayout.SHEMERLY, PageLayout.MADANI_1440))
        assertEquals(521, map(604, PageLayout.MADANI_1440, PageLayout.SHEMERLY))
    }

    @Test
    fun `a Shemerly page maps to the 15-line IndoPak page with its verses`() {
        assertEquals(27, map(24, PageLayout.SHEMERLY, PageLayout.INDOPAK_15_LINES))
        assertEquals(41, map(34, PageLayout.SHEMERLY, PageLayout.INDOPAK_15_LINES))
        assertEquals(610, map(521, PageLayout.SHEMERLY, PageLayout.INDOPAK_15_LINES))
    }

    /**
     * Shemerly pages are sent as 1440 pages until the server has a Shemerly mushaf, so these come back one page off.
     * Accepted: the Android app, which maps Shemerly through its 1440 layout by the same rule, gets the same pages.
     */
    @Test
    fun `only seven Shemerly pages don't round-trip through the 1440 layout`() {
        val changed = (1..PageLayout.SHEMERLY.pageCount)
            .associateWith { map(map(it, PageLayout.SHEMERLY, PageLayout.MADANI_1440), PageLayout.MADANI_1440, PageLayout.SHEMERLY) }
            .filter { (page, back) -> page != back }

        assertEquals(mapOf(9 to 10, 364 to 363, 379 to 380, 422 to 423, 495 to 494, 500 to 501, 516 to 517), changed)
    }

    private fun map(page: Int, source: PageLayout, target: PageLayout) = ReadingBookmarkPageMapper.mapPage(page, source, target)
}
