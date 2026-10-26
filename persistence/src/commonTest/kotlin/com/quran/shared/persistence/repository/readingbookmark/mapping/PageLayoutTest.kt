package com.quran.shared.persistence.repository.readingbookmark.mapping

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageLayoutTest {

    @Test
    fun `each layout has its page count`() {
        assertEquals(
            mapOf(
                PageLayout.MADANI_1440 to 604,
                PageLayout.MADANI_1405 to 604,
                PageLayout.INDOPAK_15_LINES to 610,
                PageLayout.INDOPAK_16_LINES to 548
            ),
            PageLayout.entries.associateWith { it.pageCount }
        )
    }

    @Test
    fun `each layout covers every verse in order without gaps`() {
        PageLayout.entries.forEach { layout ->
            assertEquals(1, layout.firstVerse(1), "$layout starts at the first verse")
            assertEquals(VERSES, layout.lastVerse(layout.pageCount), "$layout ends at the last verse")
            (1..layout.pageCount).forEach { page ->
                assertTrue(layout.firstVerse(page) <= layout.lastVerse(page), "$layout page $page")
            }
            (1 until layout.pageCount).forEach { page ->
                val next = layout.firstVerse(page + 1)
                val last = layout.lastVerse(page)
                assertTrue(next == last + 1 || next == last, "$layout pages $page and ${page + 1}: no gap, at most one shared verse")
            }
        }
    }

    @Test
    fun `only the 16-line IndoPak layout shares a verse between pages`() {
        val shared = PageLayout.entries.associateWith { layout ->
            (1 until layout.pageCount).count { layout.firstVerse(it + 1) == layout.lastVerse(it) }
        }

        assertEquals(0, shared.getValue(PageLayout.MADANI_1440))
        assertEquals(0, shared.getValue(PageLayout.MADANI_1405))
        assertEquals(0, shared.getValue(PageLayout.INDOPAK_15_LINES))
        assertTrue(shared.getValue(PageLayout.INDOPAK_16_LINES) > 0)
    }

    @Test
    fun `a verse's page is the first page holding it`() {
        PageLayout.entries.forEach { layout ->
            (1..VERSES).forEach { verse ->
                val expected = (1..layout.pageCount).first { verse in layout.firstVerse(it)..layout.lastVerse(it) }
                assertEquals(expected, layout.pageOf(verse), "$layout verse $verse")
            }
        }
    }

    private companion object {
        const val VERSES = 6236
    }
}
