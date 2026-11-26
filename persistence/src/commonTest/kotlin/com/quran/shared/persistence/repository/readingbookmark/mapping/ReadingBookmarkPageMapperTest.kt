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
     * Historical 1440 stand-in bookmarks can lose a page when crossing layouts. Native ID 27 avoids this
     * projection entirely. Preserve the known lossy cases rather than claiming every old page can be recovered.
     */
    @Test
    fun `historical Shemerly projections have ten known lossy round trips`() {
        val changed = (1..PageLayout.SHEMERLY.pageCount)
            .associateWith { map(map(it, PageLayout.SHEMERLY, PageLayout.MADANI_1440), PageLayout.MADANI_1440, PageLayout.SHEMERLY) }
            .filter { (page, back) -> page != back }

        assertEquals(mapOf(10 to 9, 207 to 206, 357 to 356, 364 to 363, 379 to 380, 400 to 399, 422 to 423, 495 to 494, 500 to 501, 516 to 517), changed)
    }

    private fun map(page: Int, source: PageLayout, target: PageLayout) = ReadingBookmarkPageMapper.mapPage(page, source, target)
}
