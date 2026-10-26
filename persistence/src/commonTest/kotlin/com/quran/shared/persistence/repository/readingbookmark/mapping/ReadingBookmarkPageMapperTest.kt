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

    private fun map(page: Int, source: PageLayout, target: PageLayout) = ReadingBookmarkPageMapper.mapPage(page, source, target)
}
