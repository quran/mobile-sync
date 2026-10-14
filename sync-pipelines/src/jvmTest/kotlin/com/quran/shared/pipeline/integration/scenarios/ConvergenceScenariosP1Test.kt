package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.pipeline.integration.DeviceTimings
import com.quran.shared.pipeline.integration.SyncDevice
import com.quran.shared.pipeline.integration.SyncScenario
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.knownIssue
import com.quran.shared.pipeline.integration.syncScenario
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Catalog section 2.11, the P1 cases: convergence and idempotency. */
class ConvergenceScenariosP1Test {

    @Test
    fun `CONV-04 three devices with the full operation set converge`() = syncScenario {
        val (d1, d2, d3) = signedIn("d1", "d2", "d3")
        // Round 1: the safe creates of CONV-03.
        d1.data.addNote("First device note", 1, 1, 1, 7)
        d1.data.addAyahBookmarkToCollection(d1.data.addCollection("Study").id, 2, 255)
        d1.data.addReadingSession(18, 1, at(1_000))
        d2.data.addNote("Second device note", 2, 153, 2, 153)
        d2.addToFavorites(2, 3)
        d2.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 18, 10, at(2_000))
        d3.data.addNote("Third device note", 36, 1, 36, 1)
        d3.data.setHighlight(5, 3, AyahHighlightColor.BLUE)
        web(ALICE).createNote("Written on the web", "67:1-67:2")
        syncUntilQuiescent(d1, d2, d3, maxRounds = 6)
        assertConverged(ALICE, d1, d2, d3)

        // Round 2: edits, a rename, an unbookmark, a color change, and a concurrent edit and delete.
        d1.data.updateNote(d1.note("First device note").id, "First device note, edited", 1, 1, 1, 7)
        d1.data.setHighlight(5, 3, AyahHighlightColor.GREEN)
        d2.data.updateCollection(d2.collectionId("Study"), "Studies")
        d2.data.deleteNote(d2.note("Third device note").id)
        d3.data.updateNote(d3.note("Third device note").id, "Third device note, edited", 36, 1, 36, 1)
        d3.removeFromFavorites(2, 3)
        d3.data.addCollection("Fresh")

        // d3 syncs first, so S6 surfaces first. Behind it, S7 rejects the note edits, and S2
        // reverts the rename d3 makes right after its push.
        knownIssue(
            "The unbookmark wedges sync (S6), note edits get 422 (S7), an echo reverts a rename (S2)",
            "S6" to FAVORITES_DELETE_REJECTED,
            "S7" to NOTE_UPDATE_REJECTED,
            "S2" to failureMentions("the rename made after a push", "but was:<Fresh>")
        ) {
            assertSucceeded(d3.syncNow())
            d3.data.updateCollection(d3.collectionId("Fresh"), "Renamed fresh")
            syncRounds(d1, d2, d3)
            syncUntilQuiescent(d1, d2, d3, maxRounds = 6)
            val names = server.state(ALICE).collections.map { it.name }
            assertEquals("Renamed fresh", names.firstOrNull { it.endsWith("resh") }, "the rename made after a push")
            assertEquals(emptySet(), server.state(ALICE).favorites)
            assertTrue("First device note, edited" in d3.noteBodies())
            assertConverged(ALICE, d1, d2, d3)
        }
    }

    @Test
    fun `CONV-05 an offline device rejoins after heavy remote activity`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        // 300 remote changes while the tablet is offline.
        val web = web(ALICE)
        repeat(200) { web.createNote("Web note number $it", "2:${it + 1}-2:${it + 1}") }
        repeat(30) { web.addToCollection(web.createCollection("Web list $it"), 3, it + 1) }
        repeat(20) { web.addBookmark(4, it + 1) }
        repeat(10) { web.updateNote(web.noteId("Web note number $it")!!, "Web note number $it, edited") }
        repeat(10) { web.deleteNote(web.noteId("Web note number ${190 + it}")!!) }
        (1..5).forEach { web.addReadingSession(18, it) }
        // 50 changes of its own.
        repeat(30) { tablet.data.addNote("Tablet note number $it", 50, it + 1, 50, it + 1) }
        repeat(5) { tablet.data.addAyahBookmarkToCollection(tablet.data.addCollection("Tablet list $it").id, 51, it + 1) }
        repeat(5) { tablet.addToFavorites(52, it + 1) }
        repeat(3) { tablet.data.setHighlight(53, it + 1, AyahHighlightColor.PINK) }
        tablet.data.addReadingSession(54, 1)
        tablet.data.addReadingSession(54, 2)

        syncUntilQuiescent(tablet)
        syncUntilQuiescent(phone, tablet)

        val state = server.state(ALICE)
        assertEquals(190 + 30, state.notes.size)
        assertTrue((0 until 30).all { i -> state.notes.any { it.body == "Tablet note number $i" } }, "every tablet note survived")
        assertEquals((0 until 5).map { "Tablet list $it" }, state.collections.map { it.name }.filter { it.startsWith("Tablet") }.sorted())
        assertEquals(25, state.favorites.size)
        assertEquals(7, state.sessions.size)
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `CONV-06 a local write triggers a sync automatically, coalescing bursts`() = syncScenario {
        // The harness note for CONV-06: a short local-change delay, then awaitSync().
        val phone = device("phone", DeviceTimings(localChangeDelay = 300.milliseconds))
        assertSucceeded(phone.login(ALICE))

        val run = coroutineScope {
            val run = async(start = CoroutineStart.UNDISPATCHED) { phone.awaitSync() }
            phone.data.addNote("One of five quick writes", 1, 1, 1, 7)
            phone.data.addCollection("Study")
            phone.addToFavorites(2, 255)
            phone.data.addReadingSession(18, 1)
            phone.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 36, 1)
            run.await()
        }

        assertSucceeded(run)
        assertEquals(
            setOf("NOTE", "COLLECTION", "COLLECTION_BOOKMARK", "READING_SESSION", "READING_BOOKMARK"),
            run.accepted.mapNotNull { it.resource }.toSet(),
            "one run pushed all five writes"
        )
        val next = phone.syncNow()
        assertSucceeded(next)
        assertEquals(0, next.posts, "nothing was left for a second run")
        assertConverged(ALICE, phone)
    }

    @Test
    fun `CONV-08 an edit to a row whose CREATE is in flight`() = syncScenario {
        val (phone) = signedIn("phone")
        val gate = server.faults.blockPost { it.carries("NOTE", "CREATE") }
        phone.data.addNote("Sent before the edit", 2, 255, 2, 255)

        phone.syncWhileHeld(gate) {
            phone.data.updateNote(phone.note("Sent before the edit").id, "Edited while the create was in flight", 2, 255, 2, 255)
        }

        // The catalog predicted S7. The edit survives the ACK, but the next GET echoes the phone's
        // own CREATE (S2) and reverts it before it is pushed. Once S2 is fixed, S7's 422 takes over.
        knownIssue(
            "The echo of the CREATE reverts the edit (S2); once it is pushed, its UPDATE gets 422 (S7)",
            "S2" to failureMentions("the latest edit on the phone", "but was:<[Sent before the edit]>"),
            "S7" to NOTE_UPDATE_REJECTED
        ) {
            assertSucceeded(phone.syncNow())
            syncUntilQuiescent(phone)
            assertEquals(listOf("Edited while the create was in flight"), phone.noteBodies(), "the latest edit on the phone")
            assertEquals(listOf("Edited while the create was in flight"), server.state(ALICE).notes.map { it.body }, "the latest edit on the server")
        }
    }

    @Test
    fun `CONV-09 a kill after the server commit but before the local apply`() = syncScenario {
        // No retries: each lost response ends the run, and restart() relaunches the app over the same data.
        val phone = device("phone", DeviceTimings(maximumRetries = 0))
        assertSucceeded(phone.login(ALICE))
        listOf("COLLECTION", "COLLECTION_BOOKMARK", "NOTE").forEach { resource -> server.faults.loseResponse { it.carries(resource) } }
        phone.writeOneOfEach()

        val first = phone.syncNow()
        assertEquals(listOf(504), first.postStatuses, "the first commit's response was lost")
        repeat(2) { assertEquals(listOf(504), phone.restart()!!.postStatuses.takeLast(1), "a later commit's response was lost") }
        assertSucceeded(phone.restart()!!)
        syncUntilQuiescent(phone)

        assertEquals(3, server.posts("phone").count { it.status == 504 }, "every fault fired")
        assertConverged(ALICE, phone)
    }

    /** Syncs [devices] for [rounds] rotating rounds; every run must succeed, so a failure names its run. */
    private suspend fun SyncScenario.syncRounds(vararg devices: SyncDevice, rounds: Int = 3) = repeat(rounds) { round ->
        devices.indices.map { devices[(it + round) % devices.size] }.forEach { assertSucceeded(it.syncNow(), it.name) }
    }
}
