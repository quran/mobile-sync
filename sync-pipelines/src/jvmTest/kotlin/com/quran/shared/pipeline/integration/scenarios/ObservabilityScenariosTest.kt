package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.TextLengthBound
import com.quran.shared.persistence.TextLengthField
import com.quran.shared.persistence.TextLengthLimitException
import com.quran.shared.pipeline.SyncErrorKind
import com.quran.shared.pipeline.SyncRunResult
import com.quran.shared.pipeline.SyncStatus
import com.quran.shared.pipeline.integration.DeviceTimings
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.server.ServerFaults
import com.quran.shared.pipeline.integration.syncScenario
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the public API reports. The sync history and held items are in [SyncHistoryScenariosTest]. */
class ObservabilityScenariosTest {

    @Test
    fun `OBS-01 length-limit errors are typed and detailed`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addNote("A note to edit later", 2, 255, 2, 255)
        val study = phone.data.addCollection("Study")
        syncUntilQuiescent(phone)
        val before = phone.snapshot()

        val failures = listOf(
            assertFailsWith<TextLengthLimitException> { phone.data.addNote("short", 1, 1, 1, 1) },
            assertFailsWith<TextLengthLimitException> { phone.data.addNote("x".repeat(10_001), 1, 1, 1, 1) },
            assertFailsWith<TextLengthLimitException> {
                phone.data.updateNote(phone.note("A note to edit later").id, "<".repeat(2_501))
            },
            assertFailsWith<TextLengthLimitException> { phone.data.addCollection(" ") },
            assertFailsWith<TextLengthLimitException> { phone.data.updateCollection(study.id, "y".repeat(256)) }
        )

        assertEquals(
            listOf(
                listOf(TextLengthField.NOTE_BODY, TextLengthBound.TOO_SHORT, 6, 5),
                listOf(TextLengthField.NOTE_BODY, TextLengthBound.TOO_LONG, 10_000, 10_001),
                listOf(TextLengthField.NOTE_BODY, TextLengthBound.TOO_LONG, 10_000, 10_004),
                listOf(TextLengthField.COLLECTION_NAME, TextLengthBound.TOO_SHORT, 1, 0),
                listOf(TextLengthField.COLLECTION_NAME, TextLengthBound.TOO_LONG, 255, 256)
            ),
            failures.map { listOf(it.field, it.bound, it.limit, it.length) }
        )
        assertEquals(before, phone.snapshot(), "no state change")
        val run = phone.syncNow()
        assertSucceeded(run)
        assertEquals(0, run.posts, "nothing to push")
    }

    @Test
    fun `OBS-02 a permanently failing sync is reported once through the public API`() = syncScenario {
        val (phone) = signedIn("phone")
        // A rejected mutation is held and doesn't fail the run; a POST that always fails still does.
        server.faults.failRequests(times = ServerFaults.ALWAYS, status = 503, code = "ServiceUnavailableError")
        phone.data.addNote("Rejected on every attempt", 2, 255, 2, 255)
        phone.data.addCollection("Study")
        phone.data.addReadingSession(18, 1)
        val statuses = CopyOnWriteArrayList<SyncStatus>()

        val run = coroutineScope {
            val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                phone.data.syncStatus.collect { statuses += it }
            }
            phone.syncNow().also { collector.cancel() }
        }

        // A StateFlow replays its current value, the login's result, to a new collector. It also
        // conflates: a value replaced before the collector runs would be missed. The collector is
        // unconfined, which in practice sees every change, but this can't prove that no value was
        // ever dropped, only that the flow showed one failure.
        assertIs<SyncStatus.Succeeded>(statuses.first(), "the login's result: $statuses")
        val attempts = statuses.drop(1).dropLast(1)
        assertEquals(
            listOf(SyncStatus.Syncing::class) + List(4) { listOf(SyncStatus.Retrying::class, SyncStatus.Syncing::class) }.flatten(),
            attempts.map { it::class },
            "the first attempt and four retries, waiting between them: $statuses"
        )
        assertEquals(run.status, statuses.last(), "then exactly one failure")
        val error = assertIs<SyncStatus.Failed>(run.status).error
        assertEquals(503 to "ServiceUnavailableError", error.httpStatus to error.serverCode)
        attempts.filterIsInstance<SyncStatus.Retrying>().forEach {
            assertEquals(error, it.error, "each wait shows the attempt's failure")
            assertTrue(it.nextAttemptAt > it.failedAt)
        }
    }

    @Test
    fun `OBS-03 a successful sync status is observable`() = syncScenario {
        val (phone) = signedIn("phone")
        val afterLogin = assertIs<SyncStatus.Succeeded>(phone.data.syncStatus.value, "the login's sync").at
        phone.data.addNote("Synced and reported", 2, 255, 2, 255)
        val started = Clock.System.now()

        val run = phone.syncNow()

        assertSucceeded(run)
        val status = assertIs<SyncStatus.Succeeded>(phone.data.syncStatus.value)
        assertEquals(run.status, status, "the public status is the run's result")
        assertTrue(status.at >= started && status.at > afterLogin, "last synced moved from $afterLogin to ${status.at}")
        assertEquals(listOf("Synced and reported"), server.state(ALICE).notes.map { it.body })
        assertConverged(ALICE, phone)
    }

    /**
     * Offline, a run waits between retries as Retrying, not Syncing: it says why and when it tries again, with the
     * retry's jitter applied. A "sync now" then runs at once instead of waiting, and the run it replaced is recorded
     * cancelled.
     */
    @Test
    fun `OBS-04 a run waiting to retry shows why and when, and a sync now runs at once`() = syncScenario {
        // A sample of 0.25 with ±50% jitter waits three quarters of the hour.
        val phone = device("phone", DeviceTimings(retryBaseDelay = 1.hours, retryJitter = 0.5, retryJitterSample = { 0.25 }))
        assertSucceeded(phone.login(ALICE))
        server.faults.networkDown()
        phone.data.addNote("Written offline", 2, 255, 2, 255)

        phone.data.triggerSync(immediately = true)

        val retrying = withTimeout(15.seconds) { phone.data.syncStatus.first { it is SyncStatus.Retrying } }
        assertIs<SyncStatus.Retrying>(retrying)
        assertEquals(SyncErrorKind.NETWORK, retrying.error.kind)
        assertEquals(45.minutes, retrying.nextAttemptAt - retrying.failedAt, "the first retry's jittered backoff")
        server.faults.networkUp()
        val run = phone.syncNow()
        assertSucceeded(run)
        assertEquals(listOf("Written offline"), server.state(ALICE).notes.map { it.body })
        assertEquals(
            listOf(SyncRunResult.SUCCEEDED, SyncRunResult.CANCELLED, SyncRunResult.SUCCEEDED),
            phone.data.syncHistory.first().map { it.result },
            "the sync now, the run whose retry it replaced, the login's"
        )
    }
}
