package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.input.ImportNote
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.pipeline.integration.DeviceTimings
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.server.ClientTimes
import com.quran.shared.pipeline.integration.syncScenario
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

        phone.data.updateNote(phone.note("First version of it").id, "Second version of it")

        assertSucceeded(phone.syncNow())
        assertEquals(listOf("Second version of it"), server.state(ALICE).notes.map { it.body })
        assertSucceeded(tablet.syncNow())
        assertEquals(listOf("Second version of it"), tablet.noteBodies())
    }

    @Test
    fun `NOTE-03 an edit made before the next sync is not reverted by the echo`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addNote("First body of it", 2, 255, 2, 255)
        assertSucceeded(phone.syncNow())

        phone.data.updateNote(phone.note("First body of it").id, "Edited body of it")

        assertSucceeded(phone.syncNow())
        assertEquals(listOf("Edited body of it"), phone.noteBodies(), "local body")
        assertEquals(listOf("Edited body of it"), server.state(ALICE).notes.map { it.body }, "server body")
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

        assertSucceeded(phone.syncNow())
        assertSucceeded(phone.syncNow())
        assertEquals(listOf("Reflect daily"), server.state(ALICE).notes.map { it.body }, "tablet's note on the server")
        assertSucceeded(tablet.syncNow())
        assertEquals(listOf("Reflect daily"), tablet.noteBodies(), "tablet's note on the tablet")
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

    /**
     * Ranges are immutable, as on the server: `updateNote` takes only the body, so an edit can't move a note,
     * and device and server never disagree on its range.
     */
    @Test
    fun `NOTE-13 a range edit never leaves device and server disagreeing`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addNote("A note to move elsewhere", 2, 255, 2, 255)
        syncUntilQuiescent(phone)

        phone.data.updateNote(phone.note("A note to move elsewhere").id, "A note that stays where it is")

        repeat(2) { assertSucceeded(phone.syncNow()) }
        assertConverged(ALICE, phone)
        assertEquals(listOf("2:255-2:255"), server.state(ALICE).notes.single().ranges, "the agreed range")
    }

    @Test
    fun `NOTE-14 a note archived on QuranReflect is not shown as active`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addNote("Archived on QuranReflect", 2, 255, 2, 255)
        syncUntilQuiescent(phone)
        val web = web(ALICE)

        web.archiveNote(web.noteId("Archived on QuranReflect")!!)
        val run = phone.syncNow()

        assertSucceeded(run)
        assertEquals(0, run.posts)
        assertNotNull(server.state(ALICE).notes.single().archivedAt, "archived, not deleted, on the server")
        // The public Note has no archived state, so of the two product decisions only "hidden" is expressible.
        assertEquals(emptyList(), phone.noteBodies(), "archived note on the phone")
    }

    @Test
    fun `NOTE-15 a vanished-resource entry is ignored`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addNote("Written on the phone", 2, 255, 2, 255)
        syncUntilQuiescent(phone)
        val web = web(ALICE)
        val id = web.noteId("Written on the phone")!!
        web.updateNote(id, "Edited on the web")
        server.faults.vanishOnce("NOTE", id)

        val first = phone.syncNow()

        assertSucceeded(first)
        assertEquals(listOf<JsonElement?>(JsonObject(emptyMap())), first.received.of("NOTE").map { it["data"] }, "the GET sent the note as {}")
        assertEquals(0, first.posts)
        assertEquals(listOf("Written on the phone"), phone.noteBodies(), "unchanged by the {} entry")

        // The fault clears when the server logs the note again; nothing resends a row the client has passed.
        web.updateNote(id, "Edited on the web", ClientTimes(updatedAt = server.state(ALICE).notes.single().clientUpdatedAt + 1))
        val second = phone.syncNow()

        assertSucceeded(second)
        assertEquals(0, second.posts)
        assertEquals(listOf("Edited on the web"), phone.noteBodies(), "the real data")
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

        phone.data.updateNote(phone.note("The original body").id, "The phone's older edit", at(1_000))
        tablet.data.updateNote(tablet.note("The original body").id, "The tablet's newer edit", at(2_000))

        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())
        syncUntilQuiescent(phone, tablet)
        assertEquals(listOf("The tablet's newer edit"), server.state(ALICE).notes.map { it.body }, "newest edit")
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `NOTE-10 a local edit beats a remote delete`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addNote("Written on the phone", 2, 255, 2, 255)
        syncUntilQuiescent(phone)
        val web = web(ALICE)
        val original = web.noteId("Written on the phone")!!

        web.deleteNote(original)
        phone.data.updateNote(phone.note("Written on the phone").id, "Edited after the web deleted it")

        assertSucceeded(phone.syncNow())
        assertEquals(listOf("Edited after the web deleted it"), phone.noteBodies(), "the edit on the phone")
        syncUntilQuiescent(phone)
        val note = server.state(ALICE).notes.single()
        assertEquals("Edited after the web deleted it", note.body)
        assertEquals(listOf("2:255-2:255"), note.ranges)
        assertTrue(note.id != original, "the note was recreated with a new id")
        assertConverged(ALICE, phone)
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

        assertSucceeded(phone.syncNow())
        assertConverged(ALICE, phone)
        val note = phone.note("A note on two passages")
        phone.data.updateNote(note.id, "An edited note on two passages")
        assertSucceeded(phone.syncNow())
        assertEquals(listOf("2:255-2:256", "3:18-3:18"), server.state(ALICE).notes.single().ranges)
        assertConverged(ALICE, phone)
    }

    @Test
    fun `NOTE-17 a lost response plus a local edit doesn't duplicate`() = syncScenario {
        // No retries, so the run ends at the lost response and the edit lands before the next one.
        val phone = device("phone", DeviceTimings(maximumRetries = 0))
        assertSucceeded(phone.login(ALICE))
        server.faults.loseResponse { it.carries("NOTE") }
        phone.data.addNote("Written before the loss", 2, 255, 2, 255)
        assertEquals(listOf(504), phone.syncNow().postStatuses, "the fault fired")

        phone.data.updateNote(phone.note("Written before the loss").id, "Edited after the loss")

        syncUntilQuiescent(phone)
        assertEquals(listOf("Edited after the loss"), phone.noteBodies(), "notes on the phone")
        assertEquals(listOf("Edited after the loss"), server.state(ALICE).notes.map { it.body }, "notes on the server")
    }

    @Test
    fun `NOTE-18 two identical pending notes plus a lost response`() = syncScenario {
        val (phone) = signedIn("phone")
        // An import skips a note with the same body and range, so writing the note twice makes the
        // pair.
        phone.data.addNote("Written twice, word for word", 2, 255, 2, 255, at(0))
        phone.data.addNote("Written twice, word for word", 2, 255, 2, 255, at(60_000))
        assertEquals(2, phone.data.notes.first().size, "two identical pending notes")
        server.faults.loseResponse { it.carries("NOTE") }

        syncUntilQuiescent(phone)
        assertEquals(2, server.state(ALICE).notes.size, "server notes")
        assertConverged(ALICE, phone)
    }

    /**
     * NOTE-19: two devices import the same legacy note before signing in, and the imports round its creation time
     * 1 ms apart (as two readers of one legacy timestamp can). The second device to sync links its copy to the
     * first one's, equal in body and ranges, instead of pushing a duplicate.
     */
    @Test
    fun `NOTE-19 the same legacy note imported on two devices before sign-in stays one note`() = syncScenario {
        val phone = device("phone")
        val tablet = device("tablet")
        val legacy = ImportNote("A legacy reflection on patience", 2, 153, 2, 153, lastUpdated = at(0), createdAt = at(0))
        phone.data.importData(PersistenceImportData(notes = listOf(legacy)))
        tablet.data.importData(PersistenceImportData(notes = listOf(legacy.copy(lastUpdated = at(-1), createdAt = at(-1)))))

        assertSucceeded(phone.login(ALICE))
        syncUntilQuiescent(phone)
        assertSucceeded(tablet.login(ALICE))
        syncUntilQuiescent(phone, tablet)

        assertEquals(listOf("A legacy reflection on patience"), server.state(ALICE).notes.map { it.body }, "server notes")
        assertEquals(T0, server.state(ALICE).notes.single().clientCreatedAt, "the first device's note")
        assertEquals(emptyList(), server.accepted("tablet").of("NOTE"), "the tablet pushed no note")
        assertConverged(ALICE, phone, tablet)
    }

    /** Saving a note with its own body (once trimmed) is no edit: the note keeps its time. It also pushes nothing. */
    @Test
    fun `NOTE-20 an unchanged edit keeps the note's time`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addNote("Patience is light", 2, 153, 2, 153, at(0))
        syncUntilQuiescent(phone)
        val synced = phone.note("Patience is light")

        phone.data.updateNote(synced.id, "\n Patience is light  ", at(5_000))
        val run = phone.syncNow()

        assertSucceeded(run)
        assertEquals(0, run.posts, "nothing pushed")
        assertEquals(listOf(synced), phone.data.notes.first(), "the note as it was, its time included")
        assertConverged(ALICE, phone)
    }
}
