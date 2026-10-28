package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.auth.model.AuthState
import com.quran.shared.persistence.input.ImportNote
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.model.Note
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.pipeline.HeldReason
import com.quran.shared.pipeline.HeldSyncItem
import com.quran.shared.pipeline.SyncResourceType
import com.quran.shared.pipeline.SyncRunResult
import com.quran.shared.pipeline.SyncRunTrigger
import com.quran.shared.pipeline.SyncStatus
import com.quran.shared.pipeline.UnsyncedItemCounts
import com.quran.shared.pipeline.integration.DeviceTimings
import com.quran.shared.pipeline.integration.SyncDevice
import com.quran.shared.pipeline.integration.SyncRun
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.server.ServerFaults
import com.quran.shared.pipeline.integration.server.text
import com.quran.shared.pipeline.integration.syncScenario
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import com.quran.shared.pipeline.SyncRun as HistoryRun

/**
 * The sync history (`syncHistory`), the held items (`heldItems`) and the unsynced items (`unsyncedItems`), through
 * the public API only.
 */
class SyncHistoryScenariosTest {

    @Test
    fun `HIST-01 a successful run is recorded with what it pushed and pulled`() = syncScenario {
        val (phone) = signedIn("phone")
        assertEquals(listOf(SyncRunTrigger.SIGN_IN to SyncRunResult.SUCCEEDED), phone.history().map { it.trigger to it.result }, "the login's run")
        phone.data.addNote("A note pushed by this run", 2, 255, 2, 255)
        phone.data.addCollection("Study")
        web(ALICE).createNote("A note pulled by this run", "1:1-1:7")

        assertSucceeded(phone.syncNow())

        val run = phone.history().first()
        assertEquals(SyncRunTrigger.MANUAL to SyncRunResult.SUCCEEDED, run.trigger to run.result)
        assertEquals(listOf(1, 2, 1, 0), listOf(run.attempts, run.pushed, run.pulled, run.held), "attempts, pushed, pulled, held")
        assertNull(run.error)
        assertEquals(assertIs<SyncStatus.Succeeded>(phone.data.syncStatus.value).at, run.endedAt, "syncStatus shows the same end")
        assertEquals(2, phone.history().size)
    }

    @Test
    fun `HIST-02 a failing run records its status and error code once, retries included`() = syncScenario {
        val (phone) = signedIn("phone")
        server.faults.failRequests(times = ServerFaults.ALWAYS, status = 503, code = "ServiceUnavailableError")
        phone.data.addNote("Rejected on every attempt", 2, 255, 2, 255)

        val status = assertIs<SyncStatus.Failed>(phone.syncNow().status)

        val history = phone.history()
        assertEquals(2, history.size, "one record for the run and its retries, after the login's")
        val run = history.first()
        assertEquals(SyncRunResult.FAILED to 5, run.result to run.attempts, "the first attempt and four retries")
        assertEquals(503 to "ServiceUnavailableError", run.error?.httpStatus to run.error?.serverCode)
        assertEquals(status.error, run.error, "the error syncStatus shows")
        assertEquals(0, run.pushed)
    }

    @Test
    fun `HIST-03 sign-out clears the history and the held items`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.importShortNote()
        assertSucceeded(phone.syncNow())
        assertEquals(1, phone.held().size)

        phone.logout()

        assertEquals(emptyList(), phone.history())
        assertEquals(emptyList(), phone.held())
    }

    @Test
    fun `HIST-04 an account switch clears the previous account's history and held items`() = syncScenario {
        val phone = device("phone")
        assertSucceeded(phone.login(ALICE))
        phone.importShortNote()
        assertSucceeded(phone.syncNow())
        assertEquals(2 to 1, phone.history().size to phone.held().size)
        phone.auth.expireSession()
        assertEquals(true, phone.syncNow().skipped, "the expired session skips the sync, unrecorded")

        assertSucceeded(phone.login(BOB))

        assertEquals(listOf(SyncRunTrigger.SIGN_IN), phone.history().map { it.trigger }, "only Bob's first run")
        assertEquals(emptyList(), phone.held())
    }

    @Test
    fun `HIST-05 a run during a push backoff is recorded as pulled only`() = syncScenario {
        val phone = device("phone", DeviceTimings(localChangeDelay = 50.milliseconds))
        assertSucceeded(phone.login(ALICE))
        // A 403 without a mutation index may be the session's scope: pushes back off for hours.
        server.faults.failRequests(times = 1, status = 403, code = "ForbiddenError")
        phone.data.addNote("Pushed once the backoff ends", 2, 255, 2, 255)
        assertIs<SyncStatus.Failed>(phone.awaitSync().status, "the local change's run")
        web(ALICE).createNote("A note from the web", "1:1-1:7")

        phone.data.addReadingSession(18, 1)
        val pulled = phone.awaitSync()

        assertIs<SyncStatus.Failed>(pulled.status, "syncStatus keeps the failure")
        val run = phone.history().first()
        assertEquals(SyncRunTrigger.LOCAL_CHANGE to SyncRunResult.PULLED_ONLY, run.trigger to run.result)
        assertEquals(0 to 1, run.pushed to run.pulled)
        assertNull(run.error)
        assertSucceeded(phone.syncNow(), "a sync now pushes anyway")
        assertEquals(SyncRunResult.SUCCEEDED to 2, phone.history().first().let { it.result to it.pushed })
    }

    @Test
    fun `HIST-06 the sync a relaunch starts while signed in is the app start, not a sign-in`() = syncScenario {
        val (phone) = signedIn("phone")

        assertSucceeded(assertNotNull(phone.restart(), "still signed in"))

        assertEquals(listOf(SyncRunTrigger.APP_START, SyncRunTrigger.SIGN_IN), phone.history().map { it.trigger })
    }

    @Test
    fun `HIST-07 a short note is held and counted, and leaves the held items as soon as it is fixed`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.importShortNote()

        assertSucceeded(phone.syncNow())

        val note = phone.note("Good")
        val held = listOf(HeldSyncItem(SyncResourceType.NOTE, note.id, null, HeldReason.TOO_SHORT, null, null, null))
        assertEquals(held, phone.held())
        assertEquals(1, phone.history().first().held, "the run counts it")
        val requests = server.requests("phone").size
        phone.awaitHeldChange(held, emptyList()) { phone.data.updateNote(note.id, "Good enough to sync") }
        assertEquals(requests, server.requests("phone").size, "no sync in between")
    }

    @Test
    fun `HIST-08 a token refresh that publishes the session again starts no run`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.auth.profileName = "Alice B."
        server.expireTokens(ALICE)

        val refreshed = phone.syncNow()
        assertSucceeded(refreshed)
        assertEquals(401, refreshed.requests.first().status, "the run refreshed the token after a 401")
        assertEquals("Alice B.", assertIs<AuthState.Success>(phone.data.authState.value).userInfo?.name, "a new profile")
        assertSucceeded(phone.syncNow())

        assertEquals(listOf(SyncRunTrigger.MANUAL, SyncRunTrigger.MANUAL, SyncRunTrigger.SIGN_IN), phone.history().map { it.trigger })
    }

    @Test
    fun `HIST-09 changes made offline are pending until a sync sends them`() = syncScenario {
        val (phone) = signedIn("phone")
        server.faults.networkDown()
        phone.addToFavorites(1, 1)
        phone.addToFavorites(2, 255)
        phone.addToFavorites(18, 10)

        assertIs<SyncStatus.Failed>(phone.syncNow().status)

        phone.awaitUnsynced(3)
        assertEquals(emptyList(), phone.held(), "nothing is held")
        server.faults.networkUp()
        assertSucceeded(phone.syncNow())
        phone.awaitUnsynced(0)
    }

    @Test
    fun `HIST-10 an edit of a synced note is pending until it is edited back`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addNote("A note to edit", 1, 1, 1, 7)
        assertSucceeded(phone.syncNow())
        phone.awaitUnsynced(0)
        val note = phone.note("A note to edit")

        phone.data.updateNote(note.id, "An edited note")

        phone.awaitUnsynced(1)
        phone.data.updateNote(note.id, "A note to edit")
        phone.awaitUnsynced(0, message = "back to the synced state")
    }

    @Test
    fun `HIST-11 saving a note unchanged moves only its edit time, which is not synced`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addNote("A note saved twice", 1, 1, 1, 7)
        val edited = phone.syncedNoteToEdit()
        val note = phone.note("A note saved twice")

        phone.watchUnsynced {
            phone.data.updateNote(edited.id, "An edited note")
            await(1, "the edit counts")

            phone.data.updateNote(note.id, note.body)

            phone.data.updateNote(edited.id, edited.body)
            await(0, "the unchanged save never counted")
        }
    }

    @Test
    fun `HIST-12 a held note counts as held, and once fixed as pending until the next sync`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.importShortNote()
        assertSucceeded(phone.syncNow())
        awaitValue(phone.data.heldItems.map { it.size }, 1)
        phone.awaitUnsynced(pending = 0, held = 1, message = "the held note counts as held")

        phone.data.updateNote(phone.note("Good").id, "Good enough to sync")

        awaitValue(phone.data.heldItems.map { it.size }, 0)
        phone.awaitUnsynced(pending = 1, message = "now pending, and no longer held")
        assertSucceeded(phone.syncNow())
        phone.awaitUnsynced(0)
    }

    @Test
    fun `HIST-13 a new collection's verses are pending with it, though they wait for its id`() = syncScenario {
        val (phone) = signedIn("phone")
        val study = phone.data.addCollection("Study")
        phone.data.addAyahBookmarkToCollection(study.id, 1, 1)
        phone.data.addAyahBookmarkToCollection(study.id, 2, 255)

        phone.awaitUnsynced(3)

        val run = phone.syncNow()
        assertSucceeded(run)
        assertEquals(3, run.accepted.size, "what was pending is what was sent")
        phone.awaitUnsynced(0)
    }

    @Test
    fun `HIST-14 deleting a synced note is pending until a sync sends the delete`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addNote("A note to delete", 1, 1, 1, 7)
        assertSucceeded(phone.syncNow())

        phone.data.deleteNote(phone.note("A note to delete").id)

        phone.awaitUnsynced(1)
        assertSucceeded(phone.syncNow())
        phone.awaitUnsynced(0)
    }

    @Test
    fun `HIST-15 a note created and deleted before any sync is not unsynced`() = syncScenario {
        val (phone) = signedIn("phone")
        val edited = phone.syncedNoteToEdit()

        phone.watchUnsynced {
            phone.data.updateNote(edited.id, "An edited note")
            await(1, "the edit counts")

            phone.data.addNote("A note that never leaves", 1, 1, 1, 7)
            phone.data.deleteNote(phone.note("A note that never leaves").id)

            phone.data.updateNote(edited.id, edited.body)
            await(0, "the created and deleted note no longer counts")
        }
    }

    @Test
    fun `HIST-16 moving a synced reading bookmark is one unsynced item`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 18, 10)
        assertSucceeded(phone.syncNow())

        phone.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 18, 11)

        phone.awaitUnsynced(1)
    }

    @Test
    fun `HIST-17 setting a reading bookmark where it is moves only its timestamp, which is not synced`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 18, 10)
        val edited = phone.syncedNoteToEdit()

        phone.watchUnsynced {
            phone.data.updateNote(edited.id, "An edited note")
            await(1, "the edit counts")

            phone.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 18, 10)

            phone.data.updateNote(edited.id, edited.body)
            await(0, "the bookmark set where it was never counted")
        }
    }

    @Test
    fun `HIST-18 a return to the foreground syncs and is recorded as one`() = syncScenario {
        val phone = device("phone", DeviceTimings(foregroundDelay = 50.milliseconds))
        assertSucceeded(phone.login(ALICE))
        phone.data.addNote("A note pushed on the return", 2, 255, 2, 255)
        web(ALICE).createNote("A note pulled on the return", "1:1-1:7")

        val run = phone.awaitSyncOf { phone.data.syncOnForeground() }

        assertSucceeded(run)
        val recorded = phone.history().first()
        assertEquals(SyncRunTrigger.FOREGROUND to SyncRunResult.SUCCEEDED, recorded.trigger to recorded.result)
        assertEquals(1 to 1, recorded.pushed to recorded.pulled, "pushed, pulled")
    }

    @Test
    fun `HIST-19 a return to the foreground during a push backoff only pulls`() = syncScenario {
        val phone = device("phone", DeviceTimings(foregroundDelay = 50.milliseconds))
        assertSucceeded(phone.login(ALICE))
        // A 403 without a mutation index may be the session's scope: pushes back off for hours.
        server.faults.failRequests(times = 1, status = 403, code = "ForbiddenError")
        phone.data.addNote("Pushed once the backoff ends", 2, 255, 2, 255)
        val failed = assertIs<SyncStatus.Failed>(phone.syncNow().status)
        web(ALICE).createNote("A note from the web", "1:1-1:7")

        val pulled = phone.awaitSyncOf { phone.data.syncOnForeground() }

        assertEquals(failed, pulled.status, "syncStatus keeps the failure")
        assertEquals(0, pulled.posts)
        val run = phone.history().first()
        assertEquals(SyncRunTrigger.FOREGROUND to SyncRunResult.PULLED_ONLY, run.trigger to run.result)
        assertEquals(0 to 1, run.pushed to run.pulled)
    }

    @Test
    fun `HIST-20 a return to the foreground while signed out sends nothing and records nothing`() = syncScenario {
        val phone = device("phone", DeviceTimings(foregroundDelay = 50.milliseconds))
        phone.data.addNote("A note written signed out", 2, 255, 2, 255)

        phone.data.syncOnForeground()
        // A smoke test: signed out, the run ends before it asks the OIDC boundary for headers, so there is nothing to
        // wait on but time, and it can't tell a skipped run from one that hasn't started. SyncClientRunRecordTest
        // proves the skip.
        delay(500.milliseconds)

        assertEquals(emptyList(), server.requests("phone"))
        assertEquals(emptyList(), phone.history())
        assertEquals(SyncStatus.Idle, phone.data.syncStatus.value)
    }

    /** Items waiting on a held one count as held with it, never as pending; pending counts only what the next sync sends. */
    @Test
    fun `HIST-21 a held collection and the verses waiting on it count as held, apart from what is pending`() = syncScenario {
        val (phone) = signedIn("phone")
        server.faults.rejectMutation { it.resource == "COLLECTION" && it.data?.text("name") == "Study" }
        val study = phone.data.addCollection("Study")
        phone.data.addAyahBookmarkToCollection(study.id, 1, 1)
        phone.data.addAyahBookmarkToCollection(study.id, 2, 255)
        assertSucceeded(phone.syncNow())
        awaitValue(phone.data.heldItems.map { held -> held.map { it.reason } }, listOf(HeldReason.REJECTED_BY_SERVER) + List(2) { HeldReason.WAITING_ON_PARENT })

        phone.data.addNote("A note written after the sync", 1, 1, 1, 7)

        phone.awaitUnsynced(pending = 1, held = 3, message = "the note pending; the collection and its verses held")
    }

    /**
     * A return to the foreground soon after a sync that pulled is dropped; after a failed one it syncs. With no
     * foreground delay, one that was not dropped would start at once and be the run the sync now waits for.
     */
    @Test
    fun `HIST-22 a return to the foreground right after a sync is skipped, but not after a failed one`() = syncScenario {
        val phone = device("phone", DeviceTimings(foregroundDelay = Duration.ZERO, foregroundSkipAfterSync = 1.hours))
        assertSucceeded(phone.login(ALICE))
        // The login's run reports its end before the scheduler is done with it; a trigger in between would wait for it.
        delay(100.milliseconds)

        phone.data.syncOnForeground()
        assertSucceeded(phone.syncNow())

        assertEquals(listOf(SyncRunTrigger.MANUAL, SyncRunTrigger.SIGN_IN), phone.history().map { it.trigger }, "no foreground run")
        server.faults.networkDown()
        phone.data.addNote("Written while the network is down", 1, 1, 1, 7)
        assertIs<SyncStatus.Failed>(phone.syncNow().status)
        server.faults.networkUp()
        assertSucceeded(phone.awaitSyncOf { phone.data.syncOnForeground() })
        assertEquals(SyncRunTrigger.FOREGROUND to SyncRunResult.SUCCEEDED, phone.history().first().let { it.trigger to it.result })
    }

    private suspend fun SyncDevice.history(): List<HistoryRun> = data.syncHistory.first()

    /** Waits for the run [start] starts: the wait begins first, so even a run that starts at once is seen. */
    private suspend fun SyncDevice.awaitSyncOf(start: suspend () -> Unit): SyncRun = coroutineScope {
        val run = async(start = CoroutineStart.UNDISPATCHED) { awaitSync() }
        start()
        run.await()
    }

    private suspend fun SyncDevice.held(): List<HeldSyncItem> = data.heldItems.first()

    private suspend fun SyncDevice.awaitUnsynced(pending: Int, held: Int = 0, message: String? = null) =
        awaitValue(data.unsyncedItems, UnsyncedItemCounts(pending, held), message)

    /** Waits until [flow] shows [expected]: a collector that joins as another leaves may first get its stale value. */
    private suspend fun <T> awaitValue(flow: Flow<T>, expected: T, message: String? = null) {
        var last: T? = null
        try {
            withTimeout(10.seconds) { flow.first { it.also { last = it } == expected } }
        } catch (e: TimeoutCancellationException) {
            fail("${message?.let { "$it: " }.orEmpty()}expected $expected, still $last")
        }
    }

    /** A synced note to edit and edit back (everything new syncs with it). */
    private suspend fun SyncDevice.syncedNoteToEdit(): Note {
        data.addNote("A note to edit and edit back", 1, 1, 1, 7)
        assertSucceeded(syncNow())
        return note("A note to edit and edit back")
    }

    /**
     * Runs [block] on one subscription to `unsyncedItems`, so no stale value can satisfy [UnsyncedWatch.await]. A
     * write that must not count goes between a counted edit and its undo: 0 then comes only if it didn't count.
     */
    private suspend fun SyncDevice.watchUnsynced(block: suspend UnsyncedWatch.() -> Unit) = coroutineScope {
        val values = Channel<UnsyncedItemCounts>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { data.unsyncedItems.collect { values.send(it) } }
        try {
            UnsyncedWatch(values).block()
        } finally {
            collector.cancel()
        }
    }

    private class UnsyncedWatch(private val values: Channel<UnsyncedItemCounts>) {
        private var last: UnsyncedItemCounts? = null

        /** Waits for [pending] pending items and none held. */
        suspend fun await(pending: Int, message: String) {
            val expected = UnsyncedItemCounts(pending, held = 0)
            try {
                withTimeout(10.seconds) { while (values.receive().also { last = it } != expected) Unit }
            } catch (e: TimeoutCancellationException) {
                fail("$message: expected $expected, still $last")
            }
        }
    }

    private suspend fun SyncDevice.importShortNote() {
        data.importData(PersistenceImportData(notes = listOf(ImportNote("Good", 1, 1, 1, 1, at(0), at(0)))))
    }

    /** Subscribes to `heldItems`, checks it shows [before], runs [change], and waits for it to show [after]. */
    private suspend fun SyncDevice.awaitHeldChange(before: List<HeldSyncItem>, after: List<HeldSyncItem>, change: suspend () -> Unit) =
        coroutineScope {
            val values = Channel<List<HeldSyncItem>>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.Default) { data.heldItems.collect { values.send(it) } }
            try {
                withTimeout(10.seconds) {
                    assertEquals(before, values.receive())
                    change()
                    while (true) if (values.receive() == after) break
                }
            } finally {
                collector.cancel()
            }
        }
}
