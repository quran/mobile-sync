package com.quran.shared.persistence.repository.readingbookmark.mapping

import com.quran.shared.persistence.model.Mushaf
import com.quran.shared.persistence.model.layout
import com.quran.shared.syncv2.types.ServerMushaf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageLayoutTest {

    @Test
    fun `each layout has its page count`() {
        assertEquals(
            mapOf(
                PageLayout.MADANI_1440 to 604,
                PageLayout.MADANI_1405 to 604,
                PageLayout.INDOPAK_15_LINES to 610,
                PageLayout.INDOPAK_16_LINES to 548,
                PageLayout.SHEMERLY to 521
            ),
            PageLayout.entries.associateWith { it.pageCount }
        )
    }

    /**
     * INDOPAK, unused, is added by hand as QCF_V2's pages (the bundle lacks it); the riwayat that number verses
     * differently have no layout. Each app mushaf is sent as a server mushaf of its layout, except Shemerly, which is
     * sent as 1440 pages until the server has its own.
     */
    @Test
    fun `each mushaf id has its layout`() {
        assertEquals(
            mapOf(
                ServerMushaf.QCF_V2 to PageLayout.MADANI_1440,
                ServerMushaf.QCF_V1 to PageLayout.MADANI_1405,
                ServerMushaf.INDOPAK to PageLayout.MADANI_1440,
                ServerMushaf.UTHMANI_HAFS to PageLayout.MADANI_1440,
                ServerMushaf.KFGQPC_HAFS to PageLayout.MADANI_1440,
                ServerMushaf.INDOPAK_15_LINES to PageLayout.INDOPAK_15_LINES,
                ServerMushaf.INDOPAK_16_LINES to PageLayout.INDOPAK_16_LINES,
                ServerMushaf.TAJWEED to PageLayout.MADANI_1405,
                ServerMushaf.QCF_TAJWEED_V4 to PageLayout.MADANI_1440,
                ServerMushaf.WARSH to null,
                ServerMushaf.DOORI to null,
                ServerMushaf.QALOUN to null,
                ServerMushaf.SHUBA to PageLayout.MADANI_1440,
                ServerMushaf.SOUSI to null,
                ServerMushaf.BAZZI to null,
                ServerMushaf.QUMBUL to null
            ),
            ServerMushaf.entries.associateWith { PageLayout.of(it.id) }
        )
        assertNull(PageLayout.of(10), "in the bundle, but not a backend mushaf")
        Mushaf.entries.forEach { mushaf ->
            val sent = if (mushaf == Mushaf.SHEMERLY) PageLayout.MADANI_1440 else mushaf.layout
            assertEquals(sent, PageLayout.of(mushaf.serverMushaf.id), "$mushaf")
        }
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
        assertEquals(0, shared.getValue(PageLayout.SHEMERLY))
        assertTrue(shared.getValue(PageLayout.INDOPAK_16_LINES) > 0)
    }

    /** From the Android app's Shemerly page starts (`scripts/data/shemerly-pages.json`): 1:1, 3:7 and 112:1. */
    @Test
    fun `the Shemerly layout's pages start where the app's do`() {
        val layout = PageLayout.SHEMERLY

        assertEquals(1 to 7, layout.firstVerse(1) to layout.lastVerse(1))
        assertEquals(299 to 300, layout.lastVerse(41) to layout.firstVerse(42))
        assertEquals(6222 to VERSES, layout.firstVerse(521) to layout.lastVerse(521))
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
