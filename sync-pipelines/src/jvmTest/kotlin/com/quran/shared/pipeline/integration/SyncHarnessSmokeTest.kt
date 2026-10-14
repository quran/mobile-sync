package com.quran.shared.pipeline.integration

import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Smoke tests for the integration harness: each one passes against today's engine. */
class SyncHarnessSmokeTest {

    @Test
    fun `first sync of an empty account downloads only Favorites`() = syncScenario {
        val phone = device("phone")

        val run = phone.login(ALICE)

        assertSucceeded(run)
        assertEquals(1, run.gets)
        assertEquals(0, run.posts)
        val collections = phone.data.collectionsWithBookmarks.first()
        assertEquals(listOf("Favorites"), collections.map { it.collection.name })
        assertTrue(collections.single().collection.isDefault)
        assertConverged(ALICE, phone)
    }

    @Test
    fun `a note round-trips between two devices`() = syncScenario {
        val phone = device("phone")
        val tablet = device("tablet")
        phone.login(ALICE)
        tablet.login(ALICE)

        phone.data.addNote("Patience is light", 2, 153, 2, 153)
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        val note = server.state(ALICE).notes.single()
        assertEquals("Patience is light", note.body)
        assertEquals(listOf("2:153-2:153"), note.ranges)
        assertEquals(listOf("Patience is light"), tablet.data.notes.first().map { it.body })
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `a collection with a verse round-trips between two devices`() = syncScenario {
        val phone = device("phone")
        val tablet = device("tablet")
        phone.login(ALICE)
        tablet.login(ALICE)

        val study = phone.data.addCollection("Study")
        phone.data.addAyahBookmarkToCollection(study.id, 3, 18)
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        val server = server.state(ALICE)
        assertEquals(setOf("3:18"), server.versesIn(server.collectionNamed("Study")!!.id))
        val onTablet = tablet.data.collectionsWithBookmarks.first().single { it.collection.name == "Study" }
        assertEquals(listOf(3 to 18), onTablet.bookmarks.map { it.sura to it.ayah })
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `a web change reaches a device without a push`() = syncScenario {
        val phone = device("phone")
        phone.login(ALICE)

        web(ALICE).createNote("Written on the web", "2:255-2:255")
        web(ALICE).addBookmark(2, 255)
        val run = phone.syncNow()

        assertSucceeded(run)
        assertEquals(0, run.posts)
        assertEquals(listOf("Written on the web"), phone.data.notes.first().map { it.body })
        val favorites = phone.data.collectionsWithBookmarks.first().single { it.collection.isDefault }
        assertEquals(listOf(2 to 255), favorites.bookmarks.map { it.sura to it.ayah })
        assertConverged(ALICE, phone)
    }

    @Test
    fun `repeat syncs of a converged device are no-ops`() = syncScenario {
        val phone = device("phone")
        phone.login(ALICE)
        phone.data.addNote("Reflect daily on it", 18, 10, 18, 10)
        phone.data.addReadingSession(18, 10)
        phone.data.setHighlight(2, 255, com.quran.shared.persistence.model.AyahHighlightColor.BLUE)
        syncUntilQuiescent(phone)
        val head = server.head(ALICE)

        repeat(3) {
            val run = phone.syncNow()
            assertSucceeded(run)
            assertEquals(0, run.posts, "sync ${it + 1} pushed")
        }
        assertEquals(head, server.head(ALICE))
        assertConverged(ALICE, phone)
    }

    @Test
    fun `a transient server error is retried within the same sync`() = syncScenario {
        val phone = device("phone")
        phone.login(ALICE)
        server.faults.failRequests(times = 1, status = 500)

        phone.data.addNote("Retried after a 500", 1, 1, 1, 7)
        val run = phone.syncNow()

        assertSucceeded(run)
        assertEquals(listOf(500, 200), run.postStatuses)
        assertEquals(1, server.state(ALICE).notes.size)
    }

    @Test
    fun `editing a synced note reaches the server`() = syncScenario {
        val phone = device("phone")
        phone.login(ALICE)
        phone.data.addNote("First version of it", 2, 255, 2, 255)
        syncUntilQuiescent(phone)
        val note = phone.data.notes.first().single()

        phone.data.updateNote(note.id, "Second version of it", 2, 255, 2, 255)

        // Today the UPDATE carries `ranges`, the server rejects it with 422, and every retry fails.
        knownIssue("S7", "Editing a synced note sends ranges and gets 422 forever", expect = syncRejectedWith(422)) {
            assertSucceeded(phone.syncNow())
            assertEquals("Second version of it", server.state(ALICE).notes.single().body)
        }
        assertEquals(List(6) { 422 }, phone.lastRun!!.postStatuses)
    }

    private companion object {
        const val ALICE = "alice"
    }
}
