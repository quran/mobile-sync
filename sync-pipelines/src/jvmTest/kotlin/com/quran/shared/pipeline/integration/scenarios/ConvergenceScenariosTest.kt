package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.pipeline.integration.DeviceTimings
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.knownIssue
import com.quran.shared.pipeline.integration.syncScenario
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/** Catalog section 2.11: convergence and idempotency. */
class ConvergenceScenariosTest {

    @Test
    fun `CONV-01 a converged device's repeated syncs do nothing`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.writeOneOfEach()
        web(ALICE).createNote("Written on the web", "67:1-67:2")
        web(ALICE).addBookmark(4, 1)
        syncUntilQuiescent(phone, tablet)
        val head = server.head(ALICE)
        val shown = phone.snapshot()

        listOf(phone, tablet).forEach { device ->
            val runs = List(3) { device.syncNow() }
            runs.forEach {
                assertSucceeded(it)
                assertEquals(0, it.posts, "${device.name} pushed")
            }
            val since = runs.flatMap { run -> run.requests.filter { it.method == "GET" } }.map { it.query["mutationsSince"] }.toSet()
            assertEquals(setOf(head.toString()), since, "${device.name} kept its token")
            assertEquals(shown, device.snapshot(), "${device.name} changed what it shows")
        }
        assertEquals(head, server.head(ALICE))
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `CONV-02 echoes of own pushes never cause a push`() = syncScenario {
        val (phone) = signedIn("phone")
        // Values the server rewrites: the note is trimmed and escaped, the name escaped.
        phone.data.addNote("  Echo test: a & b <c>  ", 2, 255, 2, 255)
        phone.data.addAyahBookmarkToCollection(phone.data.addCollection("Q&A <Tafsir>").id, 3, 18)
        phone.addToFavorites(1, 1)
        phone.data.setHighlight(2, 255, AyahHighlightColor.BLUE)
        phone.data.addReadingSession(18, 1)
        phone.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 18, 10)

        assertSucceeded(phone.syncNow())
        val second = phone.syncNow()

        assertSucceeded(second)
        assertEquals(0, second.posts)
        assertConverged(ALICE, phone)

        // The server trims reading bookmark names, and the client keeps the raw name it pushed
        // (S13). Today the next sync's echo of this device's own push (S2) overwrites it, which
        // hides S13 from any check made after a second sync.
        phone.data.renameReadingBookmark(ReadingBookmarkSlot.TEAL, "  Juz 30  ")
        assertSucceeded(phone.syncNow())
        assertEquals(listOf("Juz 30"), server.state(ALICE).readingBookmarks.map { it.name })
        knownIssue(
            "S13",
            "The client keeps a raw reading bookmark name that the server trims",
            expect = failureMentions("name after the push", "expected:<[Juz 30]> but was:<[  Juz 30  ]>")
        ) {
            assertEquals(listOf("Juz 30"), phone.data.readingBookmarks.first().map { it.name }, "name after the push")
        }
        val again = phone.syncNow()
        assertSucceeded(again)
        assertEquals(0, again.posts, "the trimmed name's echo was pushed back")
    }

    @Test
    fun `CONV-03 three devices with mixed safe operations converge`() = syncScenario {
        val (d1, d2, d3) = signedIn("d1", "d2", "d3")
        val web = web(ALICE)
        // Round 1: creates and Favorites adds on every side.
        d1.data.addNote("First device note", 1, 1, 1, 7)
        d1.data.addAyahBookmarkToCollection(d1.data.addCollection("Study").id, 2, 255)
        d1.addToFavorites(1, 1)
        d1.data.addReadingSession(18, 1, at(1_000))
        d1.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 18, 10, at(1_000))
        d2.data.addNote("Second device note", 2, 153, 2, 153)
        d2.addToFavorites(2, 3)
        d2.data.addReadingSession(3, 7, at(2_000))
        d2.data.setPageReadingBookmark(ReadingBookmarkSlot.ORANGE, 300, at(2_000))
        d2.data.addCollection("Temporary")
        d3.data.addAyahBookmarkToCollection(d3.data.addCollection("Memorize").id, 3, 18)
        d3.data.addNote("Third device note", 36, 1, 36, 1)
        d3.addToFavorites(2, 3)
        d3.data.setHighlight(5, 3, AyahHighlightColor.BLUE)
        web.createNote("Written on the web", "67:1-67:2")
        web.addBookmark(4, 1)
        web.createCollection("Web list")
        syncUntilQuiescent(d1, d2, d3, maxRounds = 6)

        // Round 2: deletes of synced data and more creates.
        d2.data.deleteNote(d2.note("First device note").id)
        d3.data.deleteCollection(d3.collectionId("Temporary"))
        d1.data.deleteReadingSession(3, 7)
        d1.data.addReadingSession(2, 255, at(3_000))
        d1.addToFavorites(112, 1)
        web.deleteCollection(web.collectionId("Web list")!!)
        web.createNote("Another web note", "112:1-112:4")
        syncUntilQuiescent(d1, d2, d3, maxRounds = 6)

        assertConverged(ALICE, d1, d2, d3)
        assertEquals(
            listOf("Another web note", "Second device note", "Third device note", "Written on the web"),
            d1.noteBodies()
        )
        assertEquals(listOf("Memorize", "Study"), d3.collectionNames())
        assertEquals(listOf("18:1", "2:255"), d2.sessionVerses())
    }

    @Test
    fun `CONV-07 a write during an in-flight sync is not lost`() = syncScenario {
        // The harness note for CONV-07: a short local-change delay, then awaitSync().
        val phone = device("phone", DeviceTimings(localChangeDelay = 300.milliseconds))
        assertSucceeded(phone.login(ALICE))
        val gate = server.faults.blockPost()
        phone.data.addNote("Pushed while blocked", 1, 1, 1, 7)

        val followUp = coroutineScope {
            val first = async { phone.syncNow() }
            withContext(Dispatchers.IO) { gate.awaitArrived() }
            // Mark before the write, so the follow-up it schedules cannot start unobserved.
            val followUp = async(start = CoroutineStart.UNDISPATCHED) { phone.awaitSync() }
            phone.data.addReadingSession(2, 255)
            gate.release()
            assertSucceeded(first.await())
            followUp.await()
        }

        assertSucceeded(followUp)
        assertEquals(1, followUp.accepted.of("READING_SESSION", "CREATE").size)
        assertEquals(listOf(2 to 255), server.state(ALICE).sessions.map { it.chapter to it.verse })
        assertEquals(1, phone.data.readingSessions.first().size)
        assertConverged(ALICE, phone)
    }
}
