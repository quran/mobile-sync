package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.model.AyahReadingBookmark
import com.quran.shared.persistence.model.EmptyReadingBookmark
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.knownIssue
import com.quran.shared.pipeline.integration.server.ClientTimes
import com.quran.shared.pipeline.integration.syncScenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
        val onTablet = assertIs<AyahReadingBookmark>(tablet.data.readingBookmarks.first().single())
        assertEquals(Triple(ReadingBookmarkSlot.TEAL, 18, 10), Triple(onTablet.slot, onTablet.sura, onTablet.ayah))
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `RB-04 clearing a slot keeps its name`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 18, 10)
        phone.data.renameReadingBookmark(ReadingBookmarkSlot.TEAL, "Khatm")
        syncUntilQuiescent(phone, tablet)

        phone.data.clearReadingBookmark(ReadingBookmarkSlot.TEAL)
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        val slot = server.state(ALICE).readingBookmarks.single { it.slot == 1 }
        assertFalse(slot.isPlaced)
        assertEquals("Khatm", slot.name)
        val onTablet = assertIs<EmptyReadingBookmark>(tablet.data.readingBookmarks.first().single())
        assertEquals(ReadingBookmarkSlot.TEAL to "Khatm", onTablet.slot to onTablet.name)
        assertEquals(emptyList(), server.requests.filter { it.status == 404 }, "no 404")
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `RB-02 setting a page reading bookmark`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")

        phone.data.setPageReadingBookmark(ReadingBookmarkSlot.ORANGE, 300)
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        val slot = server.state(ALICE).readingBookmarks.single { it.slot == 2 }
        assertEquals(Triple("page", "pages_604", 300), Triple(slot.type, slot.group, slot.key))
        val onTablet = assertIs<PageReadingBookmark>(tablet.data.readingBookmarks.first().single())
        assertEquals(ReadingBookmarkSlot.ORANGE to 300, onTablet.slot to onTablet.page)
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `RB-03 renaming a slot`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")

        phone.data.renameReadingBookmark(ReadingBookmarkSlot.RED, "Khatm")
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        assertEquals("Khatm", server.state(ALICE).readingBookmarks.single { it.slot == 3 }.name)
        val onTablet = tablet.data.readingBookmarks.first().single()
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
            val bookmark = assertIs<AyahReadingBookmark>(it.data.readingBookmarks.first().single(), it.name)
            assertEquals(36 to 1, bookmark.sura to bookmark.ayah, it.name)
        }
        assertConverged(ALICE, phone, tablet)
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

        coroutineScope {
            val sync = async { phone.syncNow() }
            withContext(Dispatchers.IO) { gate.awaitArrived() }
            phone.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 67, 1, at(2_000))
            gate.release()
            assertSucceeded(sync.await())
        }

        knownIssue(
            "S18",
            "A remote reading-bookmark apply loses a mid-sync local edit",
            // The catalog suspected this. Applying the web's slot after the POST clears the phone's newer edit.
            expect = failureMentions("the latest slot on the phone", "expected:<(67, 1)> but was:<(36, 1)>")
        ) {
            syncUntilQuiescent(phone)
            val onPhone = assertIs<AyahReadingBookmark>(phone.data.readingBookmarks.first().single())
            assertEquals(67 to 1, onPhone.sura to onPhone.ayah, "the latest slot on the phone")
            val slot = server.state(ALICE).readingBookmarks.single { it.slot == 1 }
            assertEquals(67 to 1, slot.key to slot.verse, "the latest slot on the server")
            assertConverged(ALICE, phone)
        }
    }
}
