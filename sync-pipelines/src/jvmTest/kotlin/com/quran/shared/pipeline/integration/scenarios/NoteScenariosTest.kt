package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.pipeline.integration.DeviceTimings
import com.quran.shared.pipeline.integration.KnownIssue
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.knownIssue
import com.quran.shared.pipeline.integration.syncScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Notes. */
class NoteScenariosTest {

    @Test
    fun `NOTE-01 a create syncs to the server and the other device`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")

        phone.data.addNote("Patience is light", 2, 153, 2, 153, at(0))
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        val note = server.state(ALICE).notes.single()
        assertEquals(listOf("2:153-2:153"), note.ranges)
        assertEquals(T0, note.clientCreatedAt)
        assertEquals(listOf("Patience is light"), tablet.noteBodies())
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `NOTE-02 editing a synced note reaches the server`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.data.addNote("First version of it", 2, 255, 2, 255)
        syncUntilQuiescent(phone, tablet)

        phone.data.updateNote(phone.note("First version of it").id, "Second version of it", 2, 255, 2, 255)

        knownIssue("S7", "Editing a synced note sends ranges in the UPDATE and gets 422 forever", expect = NOTE_UPDATE_REJECTED) {
            assertSucceeded(phone.syncNow())
            assertEquals(listOf("Second version of it"), server.state(ALICE).notes.map { it.body })
            assertSucceeded(tablet.syncNow())
            assertEquals(listOf("Second version of it"), tablet.noteBodies())
        }
    }

    @Test
    fun `NOTE-03 an edit made before the next sync is not reverted by the echo`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addNote("First body of it", 2, 255, 2, 255)
        assertSucceeded(phone.syncNow())

        phone.data.updateNote(phone.note("First body of it").id, "Edited body of it", 2, 255, 2, 255)

        knownIssue(
            "The echo reverts the edit (S2); once it is pushed, its UPDATE gets 422 (S7)",
            "S2" to failureMentions("local body", "First body of it"),
            "S7" to NOTE_UPDATE_REJECTED
        ) {
            assertSucceeded(phone.syncNow())
            assertEquals(listOf("Edited body of it"), phone.noteBodies(), "local body")
            assertEquals(listOf("Edited body of it"), server.state(ALICE).notes.map { it.body }, "server body")
        }
    }

    @Test
    fun `NOTE-04 deleting a synced note`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.data.addNote("Delete me after sync", 2, 255, 2, 255)
        syncUntilQuiescent(phone, tablet)

        phone.data.deleteNote(phone.note("Delete me after sync").id)
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        assertEquals(emptyList(), server.state(ALICE).notes)
        assertEquals(emptyList(), tablet.noteBodies())
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `NOTE-06 a deleted never-sent note doesn't delete another device's identical note`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.data.addNote("Reflect daily", 2, 255, 2, 255, at(0))
        phone.data.deleteNote(phone.note("Reflect daily").id)
        tablet.data.addNote("Reflect daily", 2, 255, 2, 255, at(10_000))
        assertSucceeded(tablet.syncNow())

        knownIssue(
            "S1",
            "A never-sent note tombstone binds another device's identical note and deletes it",
            expect = failureMentions("tablet's note on the server")
        ) {
            assertSucceeded(phone.syncNow())
            assertSucceeded(phone.syncNow())
            assertEquals(listOf("Reflect daily"), server.state(ALICE).notes.map { it.body }, "tablet's note on the server")
            assertSucceeded(tablet.syncNow())
            assertEquals(listOf("Reflect daily"), tablet.noteBodies(), "tablet's note on the tablet")
        }
    }

    @Test
    fun `NOTE-07 a web edit is applied`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addNote("Written on the phone", 2, 255, 2, 255)
        syncUntilQuiescent(phone)
        val web = web(ALICE)

        web.updateNote(web.noteId("Written on the phone")!!, "Edited on the web")
        val run = phone.syncNow()

        assertSucceeded(run)
        assertEquals(0, run.posts)
        assertEquals(listOf("Edited on the web"), phone.noteBodies())
        assertConverged(ALICE, phone)
    }

    @Test
    fun `NOTE-16 a lost CREATE response doesn't duplicate`() = syncScenario {
        val (phone) = signedIn("phone")
        server.faults.loseResponse { it.carries("NOTE") }

        phone.data.addNote("Response lost on the way", 1, 1, 1, 7)
        syncUntilQuiescent(phone)

        assertEquals(1, server.state(ALICE).notes.size)
        assertEquals(listOf("Response lost on the way"), phone.noteBodies())
        assertEquals(1, server.posts("phone").count { it.status == 504 }, "the fault fired")
        assertConverged(ALICE, phone)
    }

    @Test
    fun `NOTE-05 a create then delete before a sync pushes nothing`() = syncScenario {
        val (phone) = signedIn("phone")

        phone.data.addNote("Gone before it was sent", 2, 255, 2, 255)
        phone.data.deleteNote(phone.note("Gone before it was sent").id)
        phone.data.addReadingSession(18, 1)
        val run = phone.syncNow()

        assertSucceeded(run)
        assertTrue(run.accepted.of("READING_SESSION").isNotEmpty(), "the session still went up")
        assertEquals(emptyList(), server.posts("phone").flatMap { it.mutations }.of("NOTE"))
        assertEquals(emptyList(), server.state(ALICE).notes)
        assertConverged(ALICE, phone)
    }

    @Test
    fun `NOTE-08 a web delete is applied`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addNote("Deleted on the web", 2, 255, 2, 255)
        syncUntilQuiescent(phone)
        val web = web(ALICE)

        web.deleteNote(web.noteId("Deleted on the web")!!)
        val run = phone.syncNow()

        assertSucceeded(run)
        assertEquals(0, run.posts)
        assertEquals(emptyList(), phone.noteBodies())
        assertConverged(ALICE, phone)
    }

    @Test
    fun `NOTE-09 concurrent edits keep the newer one`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.data.addNote("The original body", 2, 255, 2, 255, at(0))
        syncUntilQuiescent(phone, tablet)

        phone.data.updateNote(phone.note("The original body").id, "The phone's older edit", 2, 255, 2, 255, at(1_000))
        tablet.data.updateNote(tablet.note("The original body").id, "The tablet's newer edit", 2, 255, 2, 255, at(2_000))

        knownIssue(
            "The phone's UPDATE gets 422 (S7); once it is accepted, remote-wins keeps the older edit (S17)",
            "S7" to NOTE_UPDATE_REJECTED,
            "S17" to failureMentions("newest edit", "but was:<[The phone's older edit]>")
        ) {
            assertSucceeded(phone.syncNow())
            assertSucceeded(tablet.syncNow())
            syncUntilQuiescent(phone, tablet)
            assertEquals(listOf("The tablet's newer edit"), server.state(ALICE).notes.map { it.body }, "newest edit")
            assertConverged(ALICE, phone, tablet)
        }
    }

    @Test
    fun `NOTE-10 a local edit beats a remote delete`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addNote("Written on the phone", 2, 255, 2, 255)
        syncUntilQuiescent(phone)
        val web = web(ALICE)
        val original = web.noteId("Written on the phone")!!

        web.deleteNote(original)
        phone.data.updateNote(phone.note("Written on the phone").id, "Edited after the web deleted it", 2, 255, 2, 255)

        knownIssue(
            "S15",
            "A remote delete beats a local note edit",
            expect = failureMentions("the edit on the phone", "expected:<[Edited after the web deleted it]> but was:<[]>")
        ) {
            assertSucceeded(phone.syncNow())
            assertEquals(listOf("Edited after the web deleted it"), phone.noteBodies(), "the edit on the phone")
            syncUntilQuiescent(phone)
            val note = server.state(ALICE).notes.single()
            assertEquals("Edited after the web deleted it", note.body)
            assertEquals(listOf("2:255-2:255"), note.ranges)
            assertTrue(note.id != original, "the note was recreated with a new id")
            assertConverged(ALICE, phone)
        }
    }

    @Test
    fun `NOTE-11 a remote edit beats a local delete`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addNote("Written on the phone", 2, 255, 2, 255)
        syncUntilQuiescent(phone)
        val web = web(ALICE)

        web.updateNote(web.noteId("Written on the phone")!!, "Edited on the web")
        phone.data.deleteNote(phone.note("Written on the phone").id)
        assertSucceeded(phone.syncNow())

        assertEquals(listOf("Edited on the web"), phone.noteBodies())
        assertEquals(listOf("Edited on the web"), server.state(ALICE).notes.map { it.body })
        syncUntilQuiescent(phone)
        assertConverged(ALICE, phone)
    }

    @Test
    fun `NOTE-12 a note with several ranges keeps all of them`() = syncScenario {
        val (phone) = signedIn("phone")
        web(ALICE).createNote("A note on two passages", "2:255-2:256", "3:18-3:18")

        // The public note model holds one range, so the device shows the first one only (S4).
        knownIssue(
            "Extra note ranges are dropped (S4); once kept, editing the body gets 422 (S7)",
            "S4" to failureMentions(
                "expected:<UserDataSnapshot(notes=[A note on two passages | 2:255-2:256,3:18-3:18]",
                "but was:<UserDataSnapshot(notes=[A note on two passages | 2:255-2:256]"
            ),
            "S7" to NOTE_UPDATE_REJECTED
        ) {
            assertSucceeded(phone.syncNow())
            assertConverged(ALICE, phone)
            val note = phone.note("A note on two passages")
            phone.data.updateNote(note.id, "An edited note on two passages", note.startSura, note.startAyah, note.endSura, note.endAyah)
            assertSucceeded(phone.syncNow())
            assertEquals(listOf("2:255-2:256", "3:18-3:18"), server.state(ALICE).notes.single().ranges)
            assertConverged(ALICE, phone)
        }
    }

    @Test
    fun `NOTE-17 a lost response plus a local edit doesn't duplicate`() = syncScenario(
        knownDuplicates = KnownIssue("S12", "A lost POST response plus a later local edit duplicates notes")
    ) {
        // No retries, so the run ends at the lost response and the edit lands before the next one.
        val phone = device("phone", DeviceTimings(maximumRetries = 0))
        assertSucceeded(phone.login(ALICE))
        server.faults.loseResponse { it.carries("NOTE") }
        phone.data.addNote("Written before the loss", 2, 255, 2, 255)
        assertEquals(listOf(504), phone.syncNow().postStatuses, "the fault fired")

        phone.data.updateNote(phone.note("Written before the loss").id, "Edited after the loss", 2, 255, 2, 255)

        knownIssue(
            "A lost response plus an edit duplicates the note (S12); once linked, the edit's UPDATE gets 422 (S7)",
            "S12" to failureMentions("notes on the phone", "but was:<[Edited after the loss, Written before the loss]>"),
            "S7" to NOTE_UPDATE_REJECTED
        ) {
            syncUntilQuiescent(phone)
            assertEquals(listOf("Edited after the loss"), phone.noteBodies(), "notes on the phone")
            assertEquals(listOf("Edited after the loss"), server.state(ALICE).notes.map { it.body }, "notes on the server")
        }
    }
}
