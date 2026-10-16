package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.auth.model.AuthState
import com.quran.shared.persistence.input.ImportNote
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.pipeline.HeldReason
import com.quran.shared.pipeline.HeldSyncItem
import com.quran.shared.pipeline.SyncResourceType
import com.quran.shared.pipeline.SyncRunResult
import com.quran.shared.pipeline.SyncRunTrigger
import com.quran.shared.pipeline.SyncStatus
import com.quran.shared.pipeline.integration.DeviceTimings
import com.quran.shared.pipeline.integration.SyncDevice
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.server.ServerFaults
import com.quran.shared.pipeline.integration.syncScenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import com.quran.shared.pipeline.SyncRun as HistoryRun

/** The sync history (`syncHistory`) and the held items (`heldItems`), through the public API only. */
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
        assertEquals(SyncRunResult.FAILED to 6, run.result to run.attempts, "the first attempt and five retries")
        assertEquals(503 to "ServiceUnavailableError", run.error?.httpStatus to run.error?.serverCode)
        assertEquals(status.error, run.error, "the error syncStatus shows")
        assertEquals(0, run.pushed)
    }

    @Test
    fun `HIST-03 a short note is held and counted, and leaves the held items as soon as it is fixed`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.importData(PersistenceImportData(notes = listOf(ImportNote("Good", 1, 1, 1, 1, at(0), at(0)))))

        assertSucceeded(phone.syncNow())

        val note = phone.note("Good")
        assertEquals(listOf(heldNote(note.id, HeldReason.TOO_SHORT)), phone.held())
        assertEquals(1, phone.history().first().held, "the run counts it")
        val requests = server.requests("phone").size
        phone.awaitHeldChange(listOf(heldNote(note.id, HeldReason.TOO_SHORT)), emptyList()) {
            phone.data.updateNote(note.id, "Good enough to sync")
        }
        assertEquals(requests, server.requests("phone").size, "no sync in between")
    }

    @Test
    fun `HIST-04 the history keeps the newest 50 runs`() = syncScenario {
        val (phone) = signedIn("phone")
        repeat(51) { assertSucceeded(phone.syncNow()) }

        val history = phone.history()

        assertEquals(50, history.size)
        assertEquals(List(50) { SyncRunTrigger.MANUAL }, history.map { it.trigger }, "the login's run was the oldest")
        assertEquals(history.sortedByDescending { it.startedAt }, history, "newest first")
    }

    @Test
    fun `HIST-05 sign-out clears the history and the held items`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.importShortNote()
        assertSucceeded(phone.syncNow())
        assertEquals(1, phone.held().size)

        phone.logout()

        assertEquals(emptyList(), phone.history())
        assertEquals(emptyList(), phone.held())
    }

    @Test
    fun `HIST-06 an account switch clears the previous account's history and held items`() = syncScenario {
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
    fun `HIST-07 a run during a push backoff is recorded as pulled only`() = syncScenario {
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
    fun `HIST-08 the sync a relaunch starts while signed in is the app start, not a sign-in`() = syncScenario {
        val (phone) = signedIn("phone")

        assertSucceeded(assertNotNull(phone.restart(), "still signed in"))

        assertEquals(listOf(SyncRunTrigger.APP_START, SyncRunTrigger.SIGN_IN), phone.history().map { it.trigger })
    }

    @Test
    fun `HIST-09 a token refresh that publishes the session again starts no run`() = syncScenario {
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

    private fun heldNote(id: String, reason: HeldReason) = HeldSyncItem(SyncResourceType.NOTE, id, null, reason, null, null, null)

    private suspend fun SyncDevice.history(): List<HistoryRun> = data.syncHistory.first()

    private suspend fun SyncDevice.held(): List<HeldSyncItem> = data.heldItems.first()

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
