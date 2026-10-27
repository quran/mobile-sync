package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.model.AyahReadingBookmark
import com.quran.shared.persistence.model.EmptyReadingBookmark
import com.quran.shared.persistence.model.Mushaf
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.server.ClientTimes
import com.quran.shared.pipeline.integration.syncScenario
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** Reading bookmarks, the three colored slots. */
class ReadingBookmarkScenariosTest {

    @Test
    fun `RB-01 setting an ayah reading bookmark`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")

        phone.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 18, 10)
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        val slot = server.state(ALICE).readingBookmarks.single { it.slot == 1 }
        assertEquals(Triple("ayah", 18, 10), Triple(slot.type, slot.key, slot.verse))
        val onTablet = assertIs<AyahReadingBookmark>(tablet.data.readingBookmarks(Mushaf.MADANI_1440).first().single())
        assertEquals(Triple(ReadingBookmarkSlot.TEAL, 18, 10), Triple(onTablet.slot, onTablet.sura, onTablet.ayah))
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `RB-04 clearing a slot keeps its name`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 18, 10)
        phone.data.renameReadingBookmark(ReadingBookmarkSlot.TEAL, "Khatm", Mushaf.MADANI_1440)
        syncUntilQuiescent(phone, tablet)

        phone.data.clearReadingBookmark(ReadingBookmarkSlot.TEAL)
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        val slot = server.state(ALICE).readingBookmarks.single { it.slot == 1 }
        assertFalse(slot.isPlaced)
        assertEquals("Khatm", slot.name)
        val onTablet = assertIs<EmptyReadingBookmark>(tablet.data.readingBookmarks(Mushaf.MADANI_1440).first().single())
        assertEquals(ReadingBookmarkSlot.TEAL to "Khatm", onTablet.slot to onTablet.name)
        assertEquals(emptyList(), server.requests.filter { it.status == 404 }, "no 404")
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `RB-02 setting a page reading bookmark`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")

        phone.data.setPageReadingBookmark(ReadingBookmarkSlot.ORANGE, 300, Mushaf.MADANI_1440)
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        val slot = server.state(ALICE).readingBookmarks.single { it.slot == 2 }
        assertEquals(Triple("page", "pages_604", 300), Triple(slot.type, slot.group, slot.key))
        val onTablet = assertIs<PageReadingBookmark>(tablet.data.readingBookmarks(Mushaf.MADANI_1440).first().single())
        assertEquals(ReadingBookmarkSlot.ORANGE to 300, onTablet.slot to onTablet.page)
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `RB-03 renaming a slot`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")

        phone.data.renameReadingBookmark(ReadingBookmarkSlot.RED, "Khatm", Mushaf.MADANI_1440)
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        assertEquals("Khatm", server.state(ALICE).readingBookmarks.single { it.slot == 3 }.name)
        val onTablet = tablet.data.readingBookmarks(Mushaf.MADANI_1440).first().single()
        assertEquals(ReadingBookmarkSlot.RED to "Khatm", onTablet.slot to onTablet.name)
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `RB-05 concurrent sets on one slot keep the newest`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")

        phone.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 18, 10, at(1_000))
        tablet.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 36, 1, at(2_000))
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())
        syncUntilQuiescent(phone, tablet)

        val slot = server.state(ALICE).readingBookmarks.single { it.slot == 1 }
        assertEquals(36 to 1, slot.key to slot.verse)
        listOf(phone, tablet).forEach {
            val bookmark = assertIs<AyahReadingBookmark>(it.data.readingBookmarks(Mushaf.MADANI_1440).first().single(), it.name)
            assertEquals(36 to 1, bookmark.sura to bookmark.ayah, it.name)
        }
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `RB-06 a web page bookmark from an IndoPak mushaf`() = syncScenario {
        val (phone) = signedIn("phone")

        web(ALICE).setPageReadingBookmark(1, 300, mushafId = 7)
        val runs = listOf(phone.syncNow(), phone.syncNow())

        runs.forEach(::assertSucceeded)
        assertEquals(0, runs.sumOf { it.posts }, "nothing pushed back")
        val slot = server.state(ALICE).readingBookmarks.single { it.slot == 1 }
        assertEquals(Triple("page", "pages_548", 300), Triple(slot.type, slot.group, slot.key), "the server keeps the IndoPak page")
        val onPhone = assertIs<PageReadingBookmark>(phone.data.readingBookmarks(Mushaf.MADANI_1440).first().single())
        assertEquals(333 to Mushaf.MADANI_1440, onPhone.page to onPhone.mushaf, "the Madani page with page 300's verses")
    }

    @Test
    fun `RB-07 a local slot edit during an in-flight sync isn't lost`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 18, 10, at(0))
        syncUntilQuiescent(phone)
        web(ALICE).setAyahReadingBookmark(1, 36, 1, times = ClientTimes(T0 + 1_000, T0 + 1_000))
        // Something to push, so the run has a POST to hold after its GET.
        phone.data.addNote("Pushed while the slot changes", 1, 1, 1, 7)
        val gate = server.faults.blockPost { it.carries("NOTE") }

        assertSucceeded(phone.syncWhileHeld(gate) { phone.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 67, 1, at(2_000)) })

        syncUntilQuiescent(phone)
        val onPhone = assertIs<AyahReadingBookmark>(phone.data.readingBookmarks(Mushaf.MADANI_1440).first().single())
        assertEquals(67 to 1, onPhone.sura to onPhone.ayah, "the latest slot on the phone")
        val slot = server.state(ALICE).readingBookmarks.single { it.slot == 1 }
        assertEquals(67 to 1, slot.key to slot.verse, "the latest slot on the server")
        assertConverged(ALICE, phone)
    }

    @Test
    fun `RB-08 a web 16-line IndoPak page reads as the page with its verses in each mushaf`() = syncScenario {
        val (phone) = signedIn("phone")

        web(ALICE).setPageReadingBookmark(1, 334, mushafId = 7)
        val runs = listOf(phone.syncNow(), phone.syncNow())

        runs.forEach(::assertSucceeded)
        assertEquals(0, runs.sumOf { it.posts }, "the pages_548 echo pushes nothing back")

        val madani = assertIs<PageReadingBookmark>(phone.data.readingBookmarks(Mushaf.MADANI_1440).first().single())
        val indoPak = assertIs<PageReadingBookmark>(phone.data.readingBookmarks(Mushaf.INDOPAK_15_LINES).first().single())
        assertEquals(371 to Mushaf.MADANI_1440, madani.page to madani.mushaf)
        assertEquals(372 to Mushaf.INDOPAK_15_LINES, indoPak.page to indoPak.mushaf)
    }

    @Test
    fun `RB-09 a 15-line IndoPak page is stored as the server numbers it and read in each mushaf`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")

        val set = assertIs<PageReadingBookmark>(phone.data.setPageReadingBookmark(ReadingBookmarkSlot.ORANGE, 537, Mushaf.INDOPAK_15_LINES))
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())
        val echoes = listOf(phone.syncNow(), tablet.syncNow())

        echoes.forEach(::assertSucceeded)
        assertEquals(0, echoes.sumOf { it.posts }, "the pages_610 echo pushes nothing back")

        assertEquals(537 to Mushaf.INDOPAK_15_LINES, set.page to set.mushaf)
        val slot = server.state(ALICE).readingBookmarks.single { it.slot == 2 }
        assertEquals("pages_610" to 536, slot.group to slot.key)
        val onTablet = assertIs<PageReadingBookmark>(tablet.data.readingBookmarks(Mushaf.MADANI_1440).first().single())
        assertEquals(536 to Mushaf.MADANI_1440, onTablet.page to onTablet.mushaf)
    }

    /**
     * The backend we'll ask for keeps the mushaf a page was set in, so another device maps it from its own layout. The
     * 1405 and 1440 layouts give page 585 the same number, but its 15-line IndoPak page differs: 591 from the 1405
     * layout, 590 from the 1440 one (RB-11).
     */
    @Test
    fun `RB-10 a page keeps its mushaf when the server returns it and maps from its own layout`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        server.account(ALICE).keepsMushafId = true

        phone.data.setPageReadingBookmark(ReadingBookmarkSlot.RED, 585, Mushaf.MADANI_1405)
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())
        val echoes = listOf(phone.syncNow(), tablet.syncNow())

        echoes.forEach(::assertSucceeded)
        assertEquals(0, echoes.sumOf { it.posts }, "the echo pushes nothing back")
        val slot = server.state(ALICE).readingBookmarks.single { it.slot == 3 }
        assertEquals(Triple("pages_604", 585, 2), Triple(slot.group, slot.key, slot.mushafId))
        val madani = assertIs<PageReadingBookmark>(tablet.data.readingBookmarks(Mushaf.MADANI_1440).first().single())
        val indoPak = assertIs<PageReadingBookmark>(tablet.data.readingBookmarks(Mushaf.INDOPAK_15_LINES).first().single())
        assertEquals(585 to Mushaf.MADANI_1440, madani.page to madani.mushaf)
        assertEquals(591 to Mushaf.INDOPAK_15_LINES, indoPak.page to indoPak.mushaf)
        assertConverged(ALICE, phone, tablet)
    }

    /**
     * Today's backend keeps only the group and returns its stand-in (5, read in the 1440 layout), so a 1405 page
     * comes back as a 1440 one. Accepted until the backend returns the written mushaf (RB-10).
     */
    @Test
    fun `RB-11 today's backend returns a 1405 page as its pages_604 stand-in`() = syncScenario {
        val (phone) = signedIn("phone")

        phone.data.setPageReadingBookmark(ReadingBookmarkSlot.RED, 585, Mushaf.MADANI_1405)
        val runs = listOf(phone.syncNow(), phone.syncNow())

        runs.forEach(::assertSucceeded)
        assertEquals(1, runs.sumOf { it.posts }, "only the set; its echo pushed nothing")
        val indoPak = assertIs<PageReadingBookmark>(phone.data.readingBookmarks(Mushaf.INDOPAK_15_LINES).first().single())
        assertEquals(590, indoPak.page, "read from the 1440 layout")
    }
}
