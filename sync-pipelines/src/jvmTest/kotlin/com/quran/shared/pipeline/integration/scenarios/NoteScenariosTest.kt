package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.knownIssue
import com.quran.shared.pipeline.integration.mutation
import com.quran.shared.pipeline.integration.syncRejectedWith
import com.quran.shared.pipeline.integration.syncScenario
import kotlin.test.Test
import kotlin.test.assertEquals

/** S7's signature: a note UPDATE carrying `ranges` gets 422. */
private val NOTE_UPDATE_REJECTED = syncRejectedWith(422, carrying = mutation("NOTE", "UPDATE", "ranges"))

/** Catalog section 2.2: notes. */
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
            "S2",
            "This device's own echo overwrites a newer local edit",
            // Once S2 is fixed the edit is pushed, and S7's 422 takes over until S7 is fixed too.
            expect = { failureMentions("local body", "First body of it")(it) || NOTE_UPDATE_REJECTED(it) }
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
}
