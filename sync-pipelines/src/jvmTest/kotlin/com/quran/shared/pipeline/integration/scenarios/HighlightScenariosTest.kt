package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.knownIssue
import com.quran.shared.pipeline.integration.syncScenario
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Highlights, stored as memberships in `system:highlights:<color>` collections. */
class HighlightScenariosTest {

    @Test
    fun `HL-01 highlighting a verse creates the color collection on first use`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")

        phone.data.setHighlight(2, 255, AyahHighlightColor.BLUE)
        val run = phone.syncNow()
        assertSucceeded(run)
        val tabletRun = tablet.syncNow()
        assertSucceeded(tabletRun)

        val state = server.state(ALICE)
        assertEquals(listOf(HIGHLIGHTS_BLUE), state.collections.map { it.name })
        val blue = tabletRun.received.of("COLLECTION").single { it.data("name") == HIGHLIGHTS_BLUE }
        assertEquals("true", blue.data("isSystem"), "the tablet's GET marks the color collection isSystem")
        assertEquals(setOf("2:255"), state.versesIn(state.collectionNamed(HIGHLIGHTS_BLUE)!!.id))
        assertEquals(listOf(HIGHLIGHTS_BLUE), run.accepted.of("COLLECTION", "CREATE").map { it.data("name") }, "color collections pushed")
        assertEquals(listOf("2:255 blue"), tablet.highlightList())
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `HL-02 changing a color`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.data.setHighlight(2, 255, AyahHighlightColor.BLUE)
        syncUntilQuiescent(phone, tablet)

        phone.data.setHighlight(2, 255, AyahHighlightColor.GREEN)
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        val state = server.state(ALICE)
        assertEquals(emptySet(), state.versesIn(state.collectionNamed(HIGHLIGHTS_BLUE)!!.id))
        assertEquals(setOf("2:255"), state.versesIn(state.collectionNamed(HIGHLIGHTS_GREEN)!!.id))
        assertEquals(listOf("2:255 green"), tablet.highlightList())
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `HL-03 deleting a highlight keeps bookmarks`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.addToFavorites(2, 255)
        phone.data.setHighlight(2, 255, AyahHighlightColor.BLUE)
        syncUntilQuiescent(phone, tablet)

        assertTrue(phone.data.deleteHighlight(2, 255))
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        val state = server.state(ALICE)
        assertEquals(emptySet(), state.versesIn(state.collectionNamed(HIGHLIGHTS_BLUE)!!.id))
        assertEquals(setOf("2:255"), state.favorites)
        listOf(phone, tablet).forEach {
            assertEquals(emptyList(), it.highlightList(), "${it.name} highlights")
            assertEquals(listOf("2:255"), it.favoriteVerses(), "${it.name} Favorites")
        }
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `HL-05 both devices first use the same color offline`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.data.setHighlight(2, 255, AyahHighlightColor.BLUE)
        tablet.data.setHighlight(3, 18, AyahHighlightColor.BLUE)

        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())
        syncUntilQuiescent(phone, tablet)

        val state = server.state(ALICE)
        assertEquals(listOf(HIGHLIGHTS_BLUE), state.collections.map { it.name })
        assertEquals(setOf("2:255", "3:18"), state.versesIn(state.collectionNamed(HIGHLIGHTS_BLUE)!!.id))
        assertTrue(server.requests.count { it.status == 409 } <= 1, "409 statuses: ${server.requests.count { it.status == 409 }}")
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `HL-06 the first login binds existing server highlight collections`() = syncScenario {
        val web = web(ALICE)
        web.highlight(2, 255, "blue")
        web.highlight(3, 18, "pink")
        web.highlight(5, 3, "pink")
        val phone = device("phone")
        phone.data.setHighlight(4, 1, AyahHighlightColor.PINK)

        val run = phone.login(ALICE)

        assertSucceeded(run)
        assertEquals(emptyList(), server.accepted("phone").of("COLLECTION", "CREATE"), "no color collection created")
        val state = server.state(ALICE)
        assertEquals(setOf("3:18", "4:1", "5:3"), state.versesIn(state.collectionNamed(HIGHLIGHTS_PINK)!!.id))
        assertEquals(listOf("2:255 blue", "3:18 pink", "4:1 pink", "5:3 pink"), phone.highlightList())
        assertConverged(ALICE, phone)
    }

    @Test
    fun `HL-04 highlight collections are hidden from the collections list`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.data.setHighlight(2, 255, AyahHighlightColor.BLUE)
        phone.data.setHighlight(3, 18, AyahHighlightColor.GREEN)
        phone.data.addAyahBookmarkToCollection(phone.data.addCollection("Study").id, 4, 1)
        phone.addToFavorites(1, 1)
        assertSucceeded(phone.syncNow())

        assertSucceeded(tablet.syncNow())

        val listed = tablet.data.collectionsWithBookmarks.first().map { it.collection }
        assertTrue(listed.first().isDefault, "Favorites is first: ${listed.map { it.name }}")
        assertEquals(listOf("Study"), listed.drop(1).map { it.name })
        assertEquals(listOf("2:255 blue", "3:18 green"), tablet.highlightList())
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `HL-07 concurrent color changes converge on one color`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.data.setHighlight(2, 255, AyahHighlightColor.BLUE, at(0))
        syncUntilQuiescent(phone, tablet)

        phone.data.setHighlight(2, 255, AyahHighlightColor.GREEN, at(1_000))
        tablet.data.setHighlight(2, 255, AyahHighlightColor.YELLOW, at(2_000))
        repeat(2) { syncAll(phone, tablet).forEach(::assertSucceeded) }
        syncUntilQuiescent(phone, tablet)

        // The catalog left this unknown. Both devices show yellow, because the flow shows one color
        // per verse, but the tablet stores the phone's green next to its yellow and the server keeps both.
        listOf(phone, tablet).forEach { assertEquals(listOf("2:255 yellow"), it.highlightList(), it.name) }
        knownIssue(
            "S24",
            "Concurrent highlight color changes leave the verse in two color collections",
            expect = failureMentions("one color on the server", "but was:<[system:highlights:green, system:highlights:yellow]>")
        ) {
            val state = server.state(ALICE)
            val colors = state.collections.filter { "2:255" in state.versesIn(it.id) }.map { it.name }.sorted()
            assertEquals(listOf("system:highlights:yellow"), colors, "one color on the server, the newest")
            assertConverged(ALICE, phone, tablet)
        }
    }
}
