package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.pipeline.SyncStatus
import com.quran.shared.pipeline.integration.DeviceTimings
import com.quran.shared.pipeline.integration.SyncDevice
import com.quran.shared.pipeline.integration.SyncScenario
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.awaitUntil
import com.quran.shared.pipeline.integration.knownIssue
import com.quran.shared.pipeline.integration.mutation
import com.quran.shared.pipeline.integration.pollUntil
import com.quran.shared.pipeline.integration.server.text
import com.quran.shared.pipeline.integration.syncRejectedWith
import com.quran.shared.pipeline.integration.syncScenario
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Catalog section 2.10, injected failures: rejections, lost responses, and network outages.
 * [ProtocolScenariosTest] holds the section's paging and protocol cases.
 */
class FailureScenariosTest {

    @Test
    fun `ERR-01 a 409 out of sync refetches and pushes again`() = syncScenario {
        val (phone) = signedIn("phone")
        server.faults.beforePost { web(ALICE).createNote("Written during the sync", "3:18-3:18") }

        phone.data.addCollection("Study")
        val run = phone.syncNow()

        assertSucceeded(run)
        assertEquals(1, run.postStatuses.count { it == 409 }, "POST statuses ${run.postStatuses}")
        assertEquals(listOf("Written during the sync"), phone.noteBodies())
        assertNotNull(server.state(ALICE).collectionNamed("Study"))
        assertConverged(ALICE, phone)
    }

    @Test
    fun `ERR-02 a lost response for a collection create`() = syncScenario {
        val (phone) = signedIn("phone")
        server.faults.loseResponse { it.carries("COLLECTION", "CREATE") }
        server.faults.loseResponse { it.carries("COLLECTION_BOOKMARK", "CREATE") }

        phone.data.addAyahBookmarkToCollection(phone.data.addCollection("Study").id, 2, 255)
        syncUntilQuiescent(phone)

        assertEquals(2, server.posts("phone").count { it.status == 504 }, "both faults fired")
        val state = server.state(ALICE)
        assertEquals(listOf("Study"), state.collections.map { it.name })
        assertEquals(setOf("2:255"), state.versesIn(state.collectionNamed("Study")!!.id))
        assertConverged(ALICE, phone)
    }

    @Test
    fun `ERR-03 a lost response for natural-key creates`() = syncScenario {
        val (phone) = signedIn("phone")
        server.faults.loseResponse { it.carries("COLLECTION_BOOKMARK") }
        server.faults.loseResponse { it.carries("READING_SESSION") }

        phone.addToFavorites(2, 255)
        phone.data.addReadingSession(18, 1)
        phone.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 36, 1)
        syncUntilQuiescent(phone)

        assertEquals(2, server.posts("phone").count { it.status == 504 }, "both faults fired")
        val state = server.state(ALICE)
        assertEquals(setOf("2:255"), state.favorites)
        assertEquals(listOf("18:1"), state.sessionVerses)
        assertEquals(listOf(1), state.readingBookmarks.map { it.slot })
        assertConverged(ALICE, phone)
    }

    @Test
    fun `ERR-04 a lost response for deletes`() = syncScenario {
        val (phone) = signedIn("phone")
        val study = phone.data.addCollection("Study")
        phone.data.addNote("Deleted with a lost response", 2, 255, 2, 255)
        phone.data.addReadingSession(18, 1)
        syncUntilQuiescent(phone)
        server.faults.loseResponse { it.carries("COLLECTION", "DELETE") }
        server.faults.loseResponse { it.carries("NOTE", "DELETE") || it.carries("READING_SESSION", "DELETE") }

        phone.data.deleteCollection(study.id)
        phone.data.deleteNote(phone.note("Deleted with a lost response").id)
        phone.data.deleteReadingSession(18, 1)
        syncUntilQuiescent(phone)

        assertTrue(server.posts("phone").any { it.status == 504 }, "a delete's response was lost")
        assertEquals(emptyList(), server.requests("phone").filter { it.status == 404 }.map { it.mutations }, "no 404")
        assertEquals(EMPTY_SNAPSHOT, phone.snapshot())
        assertConverged(ALICE, phone)
    }

    @Test
    fun `ERR-05 a UniqueViolationError is not treated as out-of-sync forever`() = syncScenario {
        val (phone) = signedIn("phone")
        server.faults.rejectMutation(
            status = 409,
            code = "UniqueViolationError",
            message = "You already have a collection with this name",
            details = buildJsonObject { put("name", "DUPLICATE") }
        ) { it.resource == "COLLECTION" && it.type == "CREATE" && it.data?.text("name") == "Study" }

        phone.data.addCollection("Study")
        phone.data.addNote("A note behind the duplicate", 2, 255, 2, 255)

        knownIssue(
            "S11",
            "A permanent 409 UniqueViolationError on one collection blocks every later phase",
            expect = syncRejectedWith(409, carrying = mutation("COLLECTION", "CREATE"))
        ) {
            assertSucceeded(phone.syncNow())
            assertEquals(listOf("A note behind the duplicate"), server.state(ALICE).notes.map { it.body })
            assertEquals(1, phone.collectionNames().size, "Study is held or repaired, not lost")
            assertSucceeded(phone.syncNow())
        }
    }

    @Test
    fun `ERR-06 a 404 after a remote delete raced a local edit`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addNote("Deleted on the web mid-sync", 2, 255, 2, 255)
        syncUntilQuiescent(phone)
        val web = web(ALICE)
        web.deleteNote(web.noteId("Deleted on the web mid-sync")!!)
        // Something to push, so the run has a POST to hold after the GET that carries the delete.
        phone.data.addReadingSession(18, 1)
        val gate = server.faults.blockPost { it.carries("READING_SESSION") }

        assertSucceeded(
            phone.syncWhileHeld(gate) {
                phone.data.updateNote(phone.note("Deleted on the web mid-sync").id, "Edited while the delete was applied", 2, 255, 2, 255)
            }
        )

        // S7's 422 rejects the note UPDATE before the server can answer 404, until S7 is fixed.
        knownIssue(
            "The skipped remote DELETE leaves an UPDATE that gets 404 (S10), or 422 first (S7)",
            "S10" to syncRejectedWith(404, carrying = mutation("NOTE", "UPDATE")),
            "S7" to NOTE_UPDATE_REJECTED
        ) {
            assertSucceeded(phone.syncNow())
            syncUntilQuiescent(phone)
            assertEquals(listOf("Edited while the delete was applied"), phone.noteBodies())
            assertEquals(listOf("Edited while the delete was applied"), server.state(ALICE).notes.map { it.body })
        }
    }

    @Test
    fun `ERR-06 variant, a 404 after a remote delete raced a local rename`() = syncScenario {
        // The note variant above hits S7 first. A collection rename shows S10 on its own. Collections
        // apply in the first phase, so the held POST is a collection's.
        val (phone) = signedIn("phone")
        phone.data.addCollection("Study")
        syncUntilQuiescent(phone)
        val web = web(ALICE)
        web.deleteCollection(web.collectionId("Study")!!)
        phone.data.addCollection("Other")
        val gate = server.faults.blockPost { it.carries("COLLECTION") }

        assertSucceeded(phone.syncWhileHeld(gate) { phone.data.updateCollection(phone.collectionId("Study"), "Memorize") })

        knownIssue(
            "S10",
            "A remote DELETE skipped for a row edited mid-sync leaves an UPDATE that gets 404 forever",
            expect = syncRejectedWith(404, carrying = mutation("COLLECTION", "UPDATE"))
        ) {
            assertSucceeded(phone.syncNow())
            syncUntilQuiescent(phone)
            assertEquals(listOf("Memorize", "Other"), phone.collectionNames())
            assertConverged(ALICE, phone)
        }
    }

    @Test
    fun `ERR-07 one poison mutation doesn't block the rest`() = syncScenario {
        val (phone) = signedIn("phone")
        server.faults.rejectMutation { it.resource == "NOTE" && it.data?.text("body") == POISON }

        phone.data.addNote(POISON, 2, 255, 2, 255)
        phone.data.addCollection("Study")
        phone.data.addReadingSession(18, 1)
        phone.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 36, 1)
        val run = phone.syncNow()

        knownIssue("S11", "One rejected mutation blocks the whole batch forever", expect = syncRejectedWith(422, carrying = mutation("NOTE", "CREATE"))) {
            assertSucceeded(run)
            val state = server.state(ALICE)
            assertNotNull(state.collectionNamed("Study"))
            assertEquals(listOf("18:1"), state.sessionVerses)
            assertEquals(listOf(1), state.readingBookmarks.map { it.slot })
            assertEquals(emptyList(), state.notes)
            assertEquals(listOf(POISON), phone.noteBodies(), "the poison note is held locally")
            assertSucceeded(phone.syncNow())
        }
    }

    @Test
    fun `ERR-08 a poison mutation with mutationIndex`() = syncScenario {
        server.faults.mutationIndex = true
        val (phone) = signedIn("phone")
        server.faults.rejectMutation { it.resource == "NOTE" && it.data?.text("body") == POISON }
        phone.writePoisonBatch()

        val run = phone.syncNow()

        knownIssue("S11", "One rejected mutation blocks the whole batch forever", expect = syncRejectedWith(422, carrying = mutation("NOTE", "CREATE"))) {
            assertSucceeded(run)
            assertTrue(run.posts <= 2 + PHASES, "no bisecting: ${run.posts} POSTs")
            assertPoisonHeld(phone)
        }
    }

    @Test
    fun `ERR-09 a transient 500`() = syncScenario {
        val (phone) = signedIn("phone")
        server.faults.failRequests(times = 1, status = 500)

        phone.data.addNote("Retried after a 500", 1, 1, 1, 7)
        val run = phone.syncNow()

        assertSucceeded(run)
        assertEquals(500, run.postStatuses.first(), "the first POST failed")
        assertEquals(200, run.postStatuses.last(), "the last POST succeeded")
        assertEquals(1, server.state(ALICE).notes.size)
        assertConverged(ALICE, phone)
    }

    @Test
    fun `ERR-10 a persistent 500 on one mutation`() = syncScenario {
        val (phone) = signedIn("phone")
        server.faults.rejectMutation(status = 500, code = "ServerError", message = "Something went wrong", details = null) {
            it.resource == "NOTE" && it.data?.text("body") == POISON
        }
        phone.writePoisonBatch()

        val run = phone.syncNow()

        knownIssue("S11", "A persistent 500 on one mutation blocks the whole batch forever", expect = syncRejectedWith(500, carrying = mutation("NOTE", "CREATE"))) {
            assertSucceeded(run)
            assertPoisonHeld(phone)
        }
    }

    @Test
    fun `ERR-11 a short network error recovers within retries`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addNote("Sent after the outage", 1, 1, 1, 7)
        // A short outage is a number of failed requests, not a time.
        // The network comes back once two requests were dropped, well within the retries.
        server.faults.networkDown()
        val run = coroutineScope {
            val sync = async { phone.syncNow() }
            awaitUntil("two requests were dropped") { server.requests("phone").count { it.status == DROPPED } >= 2 }
            server.faults.networkUp()
            sync.await()
        }

        assertSucceeded(run)
        assertTrue(run.requests.count { it.status == DROPPED } >= 2, "dropped requests: ${run.requests.count { it.status == DROPPED }}")
        assertEquals(1, server.state(ALICE).notes.size)
        assertConverged(ALICE, phone)
    }

    @Test
    fun `ERR-12 a long outage recovers without user action`() = syncScenario {
        // A short local-change delay and periodic interval stand in for production's 5 s and 30 min,
        // so the outage outlasts the retries in well under a second.
        val phone = device("phone", DeviceTimings(localChangeDelay = 50.milliseconds, periodicInterval = PERIODIC))
        assertSucceeded(phone.login(ALICE))
        server.faults.networkDown()

        val failed = coroutineScope {
            val failed = async(start = CoroutineStart.UNDISPATCHED) { phone.awaitSync() }
            phone.data.addNote("Written during the outage", 1, 1, 1, 7)
            failed.await()
        }
        assertIs<SyncStatus.Failed>(failed.status, "the retries ran out during the outage")
        server.faults.networkUp()

        knownIssue(
            "S21",
            "After the retries run out, sync stops until something triggers it",
            expect = failureMentions("the note reached the server after the outage", "but was:<[]>")
        ) {
            pollUntil(PERIODIC * 3) { server.state(ALICE).notes.isNotEmpty() }
            assertEquals(listOf("Written during the outage"), server.state(ALICE).notes.map { it.body }, "the note reached the server after the outage")
        }
    }

    private suspend fun SyncDevice.writePoisonBatch() {
        data.addNote(POISON, 2, 255, 2, 255)
        data.addCollection("Study")
        data.addReadingSession(18, 1)
        data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 36, 1)
    }

    private suspend fun SyncScenario.assertPoisonHeld(phone: SyncDevice) {
        val state = server.state(ALICE)
        assertNotNull(state.collectionNamed("Study"))
        assertEquals(listOf("18:1"), state.sessionVerses)
        assertEquals(listOf(1), state.readingBookmarks.map { it.slot })
        assertEquals(emptyList(), state.notes)
        assertEquals(listOf(POISON), phone.noteBodies(), "the poison note is held locally")
        assertSucceeded(phone.syncNow())
    }

    private companion object {
        const val POISON = "A note the server always rejects"

        /** [com.quran.shared.pipeline.integration.server.RequestRecord.status] of a dropped connection. */
        const val DROPPED = -1

        /** Sync phases: collections and bookmarks, memberships, then everything else. */
        const val PHASES = 3

        val PERIODIC = 500.milliseconds
    }
}
