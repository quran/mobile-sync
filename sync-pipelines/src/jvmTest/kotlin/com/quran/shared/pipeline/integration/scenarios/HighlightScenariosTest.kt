package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.server.CollectionRules
import com.quran.shared.pipeline.integration.syncScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Catalog section 2.6: highlights, stored as memberships in `system:highlights:<color>` collections. */
class HighlightScenariosTest {

    @Test
    fun `HL-01 highlighting a verse creates the color collection on first use`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")

        phone.data.setHighlight(2, 255, AyahHighlightColor.BLUE)
        val run = phone.syncNow()
        assertSucceeded(run)
        assertSucceeded(tablet.syncNow())

        val state = server.state(ALICE)
        assertEquals(listOf(BLUE), state.collections.map { it.name })
        assertTrue(CollectionRules.isSystemName(BLUE))
        assertEquals(setOf("2:255"), state.versesIn(state.collectionNamed(BLUE)!!.id))
        assertEquals(listOf(BLUE), run.accepted.of("COLLECTION", "CREATE").map { it.data("name") }, "color collections pushed")
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
        assertEquals(emptySet(), state.versesIn(state.collectionNamed(BLUE)!!.id))
        assertEquals(setOf("2:255"), state.versesIn(state.collectionNamed(GREEN)!!.id))
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
        assertEquals(emptySet(), state.versesIn(state.collectionNamed(BLUE)!!.id))
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
        assertEquals(listOf(BLUE), state.collections.map { it.name })
        assertEquals(setOf("2:255", "3:18"), state.versesIn(state.collectionNamed(BLUE)!!.id))
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
        assertEquals(setOf("3:18", "4:1", "5:3"), state.versesIn(state.collectionNamed(PINK)!!.id))
        assertEquals(listOf("2:255 blue", "3:18 pink", "4:1 pink", "5:3 pink"), phone.highlightList())
        assertConverged(ALICE, phone)
    }

    private companion object {
        const val BLUE = "system:highlights:blue"
        const val GREEN = "system:highlights:green"
        const val PINK = "system:highlights:pink"
    }
}
