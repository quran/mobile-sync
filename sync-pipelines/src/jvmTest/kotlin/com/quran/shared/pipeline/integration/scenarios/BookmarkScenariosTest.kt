package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.knownIssue
import com.quran.shared.pipeline.integration.mutation
import com.quran.shared.pipeline.integration.syncRejectedWith
import com.quran.shared.pipeline.integration.syncScenario
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S6's signature: the retried Favorites membership DELETE gets 404 "bookmark is not found". */
private val FAVORITES_DELETE_REJECTED = syncRejectedWith(404, carrying = mutation("COLLECTION_BOOKMARK", "DELETE"))

/** Catalog section 2.5: ayah bookmarks, Favorites and collection memberships. */
class BookmarkScenariosTest {

    @Test
    fun `BMK-01 add a verse to Favorites`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")

        phone.addToFavorites(2, 255)
        assertSucceeded(phone.syncNow())
        val run = tablet.syncNow()

        assertSucceeded(run)
        val bookmark = assertNotNull(server.state(ALICE).bookmarkAt(2, 255))
        assertTrue(bookmark.inDefault)
        val received = run.requests.filter { it.method == "GET" }.flatMap { get ->
            (Json.parseToJsonElement(get.response!!).jsonObject["data"]!!.jsonObject["mutations"] as JsonArray).map { it as JsonObject }
        }
        assertTrue(
            received.of("COLLECTION_BOOKMARK").any { it.data("collectionId") == "__default__" && it.data("bookmarkId") == bookmark.id },
            "GET carries the __default__ membership"
        )
        assertEquals(listOf("2:255"), tablet.favoriteVerses())
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `BMK-02 remove a verse from Favorites that is in no other collection`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.addToFavorites(2, 255)
        syncUntilQuiescent(phone, tablet)

        phone.removeFromFavorites(2, 255)

        knownIssue("S6", "Removing a verse from Favorites wedges sync permanently", expect = FAVORITES_DELETE_REJECTED) {
            assertSucceeded(phone.syncNow())
            assertSucceeded(tablet.syncNow())
            assertSucceeded(phone.syncNow())
            assertNull(server.state(ALICE).bookmarkAt(2, 255))
            assertEquals(emptyList(), tablet.favoriteVerses())
        }
    }

    @Test
    fun `BMK-04 add a verse to a custom collection`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        val study = phone.data.addCollection("Study")
        syncUntilQuiescent(phone, tablet)

        phone.data.addAyahBookmarkToCollection(study.id, 3, 18)
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        val state = server.state(ALICE)
        assertEquals(setOf("3:18"), state.versesIn(state.collectionNamed("Study")!!.id))
        assertEquals(listOf("3:18"), tablet.versesIn("Study"))
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `BMK-05 remove a verse from a custom collection in one sync attempt`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addAyahBookmarkToCollection(phone.data.addCollection("Study").id, 3, 18)
        syncUntilQuiescent(phone)

        phone.removeFromCollection("Study", 3, 18)
        val run = phone.syncNow()

        knownIssue(
            "S6",
            "A COLLECTION_BOOKMARK DELETE ACK has no resourceId, so the first attempt fails and a retry heals it",
            expect = failureMentions("GETs in the run", "expected:<1> but was:<2>")
        ) {
            assertSucceeded(run)
            assertEquals(1, run.gets, "GETs in the run")
            assertEquals(1, run.posts, "POSTs in the run")
        }
        assertSucceeded(run)
        val state = server.state(ALICE)
        assertEquals(emptySet(), state.versesIn(state.collectionNamed("Study")!!.id))
        assertConverged(ALICE, phone)
    }

    @Test
    fun `BMK-07 replacing with an empty list unbookmarks the verse`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.addToFavorites(2, 255)
        phone.data.setHighlight(2, 255, AyahHighlightColor.BLUE)
        phone.addToFavorites(3, 18)
        syncUntilQuiescent(phone, tablet)

        // Variant 1: the verse is highlighted, so the server keeps the bookmark and clears only the flag.
        phone.data.replaceAyahBookmarkCollections(2, 255, emptyList())
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())
        assertEquals(setOf("3:18"), server.state(ALICE).favorites)
        listOf(phone, tablet).forEach {
            assertEquals(listOf("3:18"), it.favoriteVerses(), "${it.name} Favorites")
            assertEquals(listOf("2:255 blue"), it.highlightList(), "${it.name} highlights")
        }
        assertConverged(ALICE, phone, tablet)

        // Variant 2: no highlight, so the server deletes the bookmark with its Favorites membership.
        phone.data.replaceAyahBookmarkCollections(3, 18, emptyList())
        knownIssue("S6", "Removing a verse from Favorites wedges sync permanently", expect = FAVORITES_DELETE_REJECTED) {
            assertSucceeded(phone.syncNow())
            assertSucceeded(tablet.syncNow())
            assertNull(server.state(ALICE).bookmarkAt(3, 18))
            assertEquals(emptyList(), tablet.favoriteVerses())
        }
    }

    @Test
    fun `BMK-08 a verse added to a collection created in the same offline session`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addAyahBookmarkToCollection(phone.data.addCollection("New").id, 2, 255)

        val run = phone.syncNow()

        assertSucceeded(run)
        assertTrue(run.postStatuses.all { it == 200 }, "POST statuses ${run.postStatuses}")
        val state = server.state(ALICE)
        assertEquals(setOf("2:255"), state.versesIn(state.collectionNamed("New")!!.id))
        assertConverged(ALICE, phone)
    }

    @Test
    fun `BMK-09 Favorites added before the first login`() = syncScenario {
        val phone = device("phone")
        phone.addToFavorites(2, 255)

        val run = phone.login(ALICE)

        assertSucceeded(run)
        assertEquals(setOf("2:255"), server.state(ALICE).favorites)
        assertTrue(run.accepted.of("COLLECTION_BOOKMARK", "CREATE").any { it.data("collectionId") == "__default__" })
        assertConverged(ALICE, phone)
    }

    @Test
    fun `BMK-10 a web bookmark goes into Favorites`() = syncScenario {
        val (phone) = signedIn("phone")

        web(ALICE).addBookmark(2, 255)
        val run = phone.syncNow()

        assertSucceeded(run)
        assertEquals(0, run.posts)
        assertEquals(listOf("2:255"), phone.favoriteVerses())
        assertConverged(ALICE, phone)
    }
}
