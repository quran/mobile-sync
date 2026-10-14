package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.pipeline.integration.SyncRun
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.knownIssue
import com.quran.shared.pipeline.integration.server.ClientTimes
import com.quran.shared.pipeline.integration.server.CollectionRules
import com.quran.shared.pipeline.integration.server.text
import com.quran.shared.pipeline.integration.syncScenario
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ayah bookmarks, Favorites and collection memberships. */
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
        assertTrue(
            run.received.of("COLLECTION_BOOKMARK").any { it.data("collectionId") == "__default__" && it.data("bookmarkId") == bookmark.id },
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

        knownIssue("S6", "Removing a verse from Favorites wedges sync permanently", expect = FAVORITES_DELETE_404) {
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
        knownIssue("S6", "Removing a verse from Favorites wedges sync permanently", expect = FAVORITES_DELETE_404) {
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

    @Test
    fun `BMK-03 removing from Favorites keeps a verse that is in another collection`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.data.addAyahBookmarkToCollection(phone.data.addCollection("Study").id, 2, 255)
        phone.addToFavorites(2, 255)
        syncUntilQuiescent(phone, tablet)

        phone.removeFromFavorites(2, 255)
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        val bookmark = assertNotNull(server.state(ALICE).bookmarkAt(2, 255), "the server keeps the bookmark")
        assertFalse(bookmark.inDefault, "the Favorites flag is cleared")
        listOf(phone, tablet).forEach {
            assertEquals(emptyList(), it.favoriteVerses(), "${it.name} Favorites")
            assertEquals(listOf("2:255"), it.versesIn("Study"), "${it.name} Study")
        }
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `BMK-06 replaceAyahBookmarkCollections sets the membership set exactly`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        val a = phone.data.addCollection("A")
        val b = phone.data.addCollection("B")
        phone.data.addAyahBookmarkToCollection(a.id, 2, 255)
        phone.addToFavorites(2, 255)
        phone.data.setHighlight(2, 255, AyahHighlightColor.BLUE)
        syncUntilQuiescent(phone, tablet)

        phone.data.replaceAyahBookmarkCollections(2, 255, listOf(a.id, b.id))
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        val state = server.state(ALICE)
        assertEquals(setOf("2:255"), state.versesIn(state.collectionNamed("A")!!.id))
        assertEquals(setOf("2:255"), state.versesIn(state.collectionNamed("B")!!.id))
        assertEquals(emptySet(), state.favorites)
        listOf(phone, tablet).forEach {
            assertEquals(listOf("2:255"), it.versesIn("A"), "${it.name} A")
            assertEquals(listOf("2:255"), it.versesIn("B"), "${it.name} B")
            assertEquals(emptyList(), it.favoriteVerses(), "${it.name} Favorites")
            assertEquals(listOf("2:255 blue"), it.highlightList(), "${it.name} highlights")
        }
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `BMK-11 a web removal from Favorites deletes the bookmark`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.addToFavorites(2, 255)
        syncUntilQuiescent(phone)

        web(ALICE).removeFromFavorites(2, 255)

        // The catalog predicted a pass. Applying the remote BOOKMARK DELETE turns the phone's
        // Favorites membership into a pending DELETE that the membership phase can no longer match
        // to the remote one, so the phone pushes it and gets 404 on every attempt.
        knownIssue("S23", "A web removal from Favorites wedges the device's sync", expect = FAVORITES_DELETE_404) {
            val runs = listOf(phone.syncNow(), phone.syncNow())
            runs.forEach(::assertSucceeded)
            assertNull(server.state(ALICE).bookmarkAt(2, 255))
            assertEquals(emptyList(), phone.favoriteVerses())
            assertEquals(0, runs.sumOf { it.posts }, "no DELETE echo")
            assertConverged(ALICE, phone)
        }
    }

    @Test
    fun `BMK-12 a web collection delete cascades to the device`() = syncScenario {
        val (phone) = signedIn("phone")
        val study = phone.data.addCollection("Study")
        phone.data.addAyahBookmarkToCollection(study.id, 2, 255)
        phone.data.addAyahBookmarkToCollection(study.id, 3, 18)
        phone.addToFavorites(3, 18)
        syncUntilQuiescent(phone)
        val web = web(ALICE)

        web.deleteCollection(web.collectionId("Study")!!)
        val run = phone.syncNow()

        assertSucceeded(run)
        assertEquals(0, run.posts)
        assertEquals(emptyList(), phone.collectionNames())
        assertEquals(listOf("3:18"), phone.favoriteVerses())
        assertTrue(phone.data.collectionsWithBookmarks.first().none { c -> c.bookmarks.any { it.sura == 2 && it.ayah == 255 } }, "2:255 is bookmarked nowhere")
        assertConverged(ALICE, phone)
    }

    @Test
    fun `BMK-13 a server bookmark with no membership is not shown`() = syncScenario {
        val (phone) = signedIn("phone")

        web(ALICE).setAyahReadingBookmark(1, 5, 3)
        val runs = listOf(phone.syncNow(), phone.syncNow())

        runs.forEach(::assertSucceeded)
        val legacy = assertNotNull(server.state(ALICE).bookmarkAt(5, 3), "slot 1 made a legacy bookmark")
        assertFalse(legacy.inDefault)
        assertTrue(phone.data.collectionsWithBookmarks.first().all { it.bookmarks.isEmpty() }, "5:3 is in no collection")
        assertEquals(0, runs.sumOf { it.posts }, "nothing pushed")
        assertConverged(ALICE, phone)
    }

    @Test
    fun `BMK-14 the same verse added to Favorites on two devices`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.addToFavorites(2, 255)
        tablet.addToFavorites(2, 255)

        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())
        syncUntilQuiescent(phone, tablet)

        assertEquals(1, server.state(ALICE).bookmarks.count { it.key == 2 && it.verse == 255 })
        listOf(phone, tablet).forEach { assertEquals(listOf("2:255"), it.favoriteVerses(), "${it.name} Favorites") }
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `BMK-15 concurrent membership changes in one collection`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.data.addAyahBookmarkToCollection(phone.data.addCollection("Study").id, 2, 255)
        syncUntilQuiescent(phone, tablet)

        phone.removeFromCollection("Study", 2, 255)
        tablet.data.addAyahBookmarkToCollection(tablet.collectionId("Study"), 3, 18)
        repeat(2) { syncAll(phone, tablet).forEach(::assertSucceeded) }

        val state = server.state(ALICE)
        assertEquals(setOf("3:18"), state.versesIn(state.collectionNamed("Study")!!.id))
        listOf(phone, tablet).forEach { assertEquals(listOf("3:18"), it.versesIn("Study"), it.name) }
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `BMK-16 adding to a collection the web deleted`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addCollection("Study")
        syncUntilQuiescent(phone)
        val web = web(ALICE)

        web.deleteCollection(web.collectionId("Study")!!)
        phone.data.addAyahBookmarkToCollection(phone.collectionId("Study"), 2, 255)

        // The catalog left this unknown. The sync completes, and the web's delete drops the membership.
        knownIssue(
            "S15",
            "A remote collection delete beats a local add to that collection",
            expect = failureMentions("the edit beats the delete", "expected:<[2:255]> but was:<null>")
        ) {
            assertSucceeded(phone.syncNow())
            syncUntilQuiescent(phone)
            assertEquals(listOf("2:255"), phone.versesIn("Study"), "the edit beats the delete")
            val state = server.state(ALICE)
            assertEquals(setOf("2:255"), state.versesIn(assertNotNull(state.collectionNamed("Study")).id))
            assertConverged(ALICE, phone)
        }
    }

    @Test
    fun `BMK-17 a bookmark delete split from its membership delete across GET pages`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.addToFavorites(2, 255)
        syncUntilQuiescent(phone)
        // The harness note for ERR-14: page with more than 1000 rows, not a page cap. 999 changes
        // come first, so the BOOKMARK DELETE ends page 1 and its Favorites membership DELETE starts page 2.
        val web = web(ALICE)
        repeat(999) {
            val verse = "2:${it % 286 + 1}"
            web.createNote("Seeded note number $it", "$verse-$verse", times = ClientTimes(createdAt = T0 + it))
        }

        web.removeFromFavorites(2, 255)

        val run = phone.syncNow()

        assertEquals(listOf("BOOKMARK DELETE", "COLLECTION_BOOKMARK DELETE"), run.firstPageBoundary(), "the deletes straddle pages 1 and 2")
        knownIssue(
            "S23",
            "A web removal from Favorites wedges the device's sync",
            // The catalog left this unknown. The client fetches every page before applying any, so the
            // split changes nothing: the phone pushes the membership DELETE and gets 404, as in BMK-11.
            expect = FAVORITES_DELETE_404
        ) {
            assertSucceeded(run)
            assertEquals(0, run.posts, "no DELETE echo")
            assertEquals(emptyList(), phone.favoriteVerses())
            assertConverged(ALICE, phone)
        }
    }

    @Test
    fun `BMK-18 page and surah bookmarks from the web are left alone`() = syncScenario {
        val (phone) = signedIn("phone")
        val web = web(ALICE)
        val page = web.addPageBookmark(300)
        val surah = web.addSurahBookmark(18)

        assertSucceeded(phone.syncNow())
        assertEquals(EMPTY_SNAPSHOT, phone.snapshot(), "the phone shows neither")
        phone.addToFavorites(2, 255)
        phone.data.addAyahBookmarkToCollection(phone.data.addCollection("Study").id, 3, 18)
        syncUntilQuiescent(phone)

        val state = server.state(ALICE)
        assertEquals(listOf(page, surah), state.bookmarks.filter { it.type != "ayah" }.map { it.id })
        assertTrue(state.bookmarks.filter { it.type != "ayah" }.all { it.inDefault }, "still in Favorites on the server")
        val ids = setOf(page, surah, CollectionRules.defaultId(page), CollectionRules.defaultId(surah))
        val touched = server.posts("phone").flatMap { it.mutations }.filter { it.data("bookmarkId") in ids || it.text("resourceId") in ids }
        assertEquals(emptyList(), touched, "the phone never pushed a change to them")
        assertConverged(ALICE, phone)
    }

    @Test
    fun `BMK-19 ayah bookmarks are mushaf-agnostic`() = syncScenario {
        val (phone) = signedIn("phone")
        web(ALICE).addBookmark(2, 255, mushafId = INDOPAK_16_LINE)

        assertSucceeded(phone.syncNow())
        assertEquals(listOf("2:255"), phone.favoriteVerses(), "the phone shows the IndoPak bookmark")
        phone.removeFromFavorites(2, 255)

        knownIssue("S6", "Removing a verse from Favorites wedges sync permanently", expect = FAVORITES_DELETE_404) {
            assertSucceeded(phone.syncNow())
            assertEquals(emptyList(), server.state(ALICE).bookmarks, "the removal deleted the same bookmark, with no second one")
            assertConverged(ALICE, phone)
        }
    }

    /** The last entry of the run's first GET page and the first entry of its second. */
    private fun SyncRun.firstPageBoundary(): List<String> {
        val (first, second) = requests.filter { it.method == "GET" && it.status == 200 }.take(2).map { page ->
            (Json.parseToJsonElement(page.response!!).jsonObject["data"]!!.jsonObject["mutations"] as JsonArray).map { it as JsonObject }
        }
        return listOf(first.last(), second.first()).map { "${it.resource} ${it.type}" }
    }

    private companion object {
        /** The 16-line IndoPak mushaf, whose pages are the `pages_548` group. */
        const val INDOPAK_16_LINE = 7
    }
}
