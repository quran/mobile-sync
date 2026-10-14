package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.model.AyahReadingBookmark
import com.quran.shared.persistence.model.EmptyReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.pipeline.integration.assertSucceeded
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
}
