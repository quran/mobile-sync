package com.quran.shared.pipeline.sync

import app.cash.sqldelight.db.QueryResult
import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.pipeline.SyncHistory
import com.quran.shared.pipeline.SyncRun
import com.quran.shared.pipeline.SyncRunResult
import com.quran.shared.pipeline.SyncRunTrigger
import com.quran.shared.pipeline.SyncStatus
import com.quran.shared.pipeline.SyncStatusTracker
import com.quran.shared.pipeline.SynchronizationEnvironment
import com.quran.shared.pipeline.inMemoryMobileSyncDatabase
import com.quran.shared.pipeline.integration.server.FakeSyncServer
import com.quran.shared.pipeline.integration.server.ServerFaults
import com.quran.shared.syncv2.types.CollectionsRepository
import com.quran.shared.syncv2.types.NoteRange
import com.quran.shared.syncv2.types.NotesRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.atomic.AtomicInteger

/**
 * [SyncClient] with the real engine over an in-memory database against the integration [FakeSyncServer]: what it
 * records per run in [SyncHistory], read back from `sync_run` itself.
 */
class SyncClientRunRecordTest {
    private val server = FakeSyncServer()
    private val database: MobileSyncDatabase = inMemoryMobileSyncDatabase()
    private val history = SyncHistory(database.synced)
    private val ended = Channel<SyncRun>(Channel.UNLIMITED)
    private val skips = Channel<Unit>(Channel.UNLIMITED)
    private val status = SyncStatusTracker()
    private val token = server.issueToken(ACCOUNT, "phone")
    private val notes = NotesRepository(database.synced)
    private val collections = CollectionsRepository(database.synced)
    private var retryBase: Duration = 5.milliseconds
    private var retryJitter = 0.0
    private var jitterSample = 0.5
    private var foregroundDelay: Duration = 1.hours

    /** How many times sync asked the session for headers. */
    private val headerRequests = AtomicInteger()

    /** When set, asking for headers waits for it, like a token refresh that hasn't answered yet. */
    @Volatile
    private var headersGate: CompletableDeferred<Unit>? = null

    /** How many more times the session gives headers before it ends; it never ends unless set. */
    @Volatile
    private var headersLeft = Int.MAX_VALUE

    @Volatile
    private var refreshes = true
    private val client by lazy {
        val account = object : SyncAccount {
            override suspend fun authHeaders(): Map<String, String> {
                headerRequests.incrementAndGet()
                headersGate?.await()
                return if (headersLeft-- > 0) mapOf("Authorization" to "Bearer $token") else emptyMap()
            }
            override suspend fun accountId() = ACCOUNT
            override suspend fun forceTokenRefresh() = refreshes.also { if (!it) headersLeft = 0 }
        }
        val observer = SyncRunObservers(history, status, object : SyncRunObserver {
            override fun syncSkipped() {
                skips.trySend(Unit)
            }

            override fun syncEnded(run: SyncRun) {
                ended.trySend(run)
            }
        })
        val retrying = FailureRetryingConfig(retryBase, 2.5, 5, jitter = retryJitter)
        val timings = SchedulerTimings(1.hours, 1.hours, 1.hours, foregroundDelay, retrying)
        val gate = object : SyncLifecycleGate {}
        SyncClient.create(database.synced, SynchronizationEnvironment(server.url), account, gate, observer, timings) { jitterSample }
    }

    @AfterTest
    fun tearDown() {
        client.close()
        server.close()
        database.driver.close()
    }

    private fun next(): SyncRun = runBlocking { withTimeout(15.seconds) { ended.receive() } }

    private fun stored(): List<SyncRun> = runBlocking { history.runs.first() }

    private fun awaitSkip() = runBlocking { withTimeout(15.seconds) { skips.receive() } }

    /** 101 notes, so the first POST lands 100 of them before the second one fails with [status]. */
    private fun failSecondPost(status: Int, code: String, beforeAnswer: () -> Unit = {}) {
        repeat(101) { notes.addNote("Note number $it, pushed in two batches", listOf(NoteRange(2, 255, 2, 255)), it.toLong()) }
        var posts = 0
        server.faults.failRequests(status = status, code = code) { request ->
            (request.isPost && ++posts == 2).also { second -> if (second) beforeAnswer() }
        }
    }

    /** Every stored value of every row, as text. */
    private fun storedText(): String = database.driver.executeQuery(null, "SELECT * FROM sync_run", { cursor ->
        val values = mutableListOf<String?>()
        while (cursor.next().value) (0 until COLUMNS).forEach { values += cursor.getString(it) ?: cursor.getLong(it)?.toString() }
        QueryResult.Value(values.joinToString("|"))
    }, 0).value

    @Test
    fun `a run that succeeds after retries is one record with its attempts and counts`() {
        server.faults.failRequests(times = 2, status = 503, code = "ServiceUnavailableError")
        notes.addNote("A note that syncs on the third try", listOf(NoteRange(2, 255, 2, 255)), 0)
        server.web(ACCOUNT).createNote("A note from the web", "1:1-1:1")

        client.triggerSyncImmediately()
        val run = next()

        assertEquals(SyncRunResult.SUCCEEDED, run.result)
        assertEquals(SyncRunTrigger.MANUAL, run.trigger)
        assertEquals(listOf(3, 1, 1, 0), listOf(run.attempts, run.pushed, run.pulled, run.held), "attempts, pushed, pulled, held")
        assertNull(run.error)
        assertEquals(listOf(run), stored())
    }

    /** A 401 refreshes the token and runs the engine again in the same attempt: the record sums both engine runs. */
    @Test
    fun `a run re-run after a token refresh counts what both engine runs pushed and pulled`() {
        repeat(101) { notes.addNote("Note number $it, pushed in two batches", listOf(NoteRange(2, 255, 2, 255)), it.toLong()) }
        server.web(ACCOUNT).createNote("A note from the web", "1:1-1:1")
        var posts = 0
        // The second POST: the first 100 notes landed, the last one is left for the engine run after the refresh.
        server.faults.failRequests(status = 401, code = "TokenExpiredError") { it.isPost && ++posts == 2 }

        client.triggerSyncImmediately()
        val run = next()

        assertEquals(listOf(200, 401, 200), server.requests("phone").filter { it.method == "POST" }.map { it.status })
        assertEquals(SyncRunResult.SUCCEEDED, run.result)
        assertEquals(listOf(1, 101, 1), listOf(run.attempts, run.pushed, run.pulled), "attempts, pushed, pulled")
        assertEquals(listOf(run), stored())
    }

    /** The status, code, counts and the error's fixed message are stored; no note text, name or server message. */
    @Test
    fun `a failing run stores no user text`() {
        server.faults.failRequests(
            times = ServerFaults.ALWAYS, status = 503, code = "ServiceUnavailableError",
            message = "Could not save \"$SECRET_NOTE\" in \"$SECRET_COLLECTION\""
        )
        notes.addNote(SECRET_NOTE, listOf(NoteRange(2, 255, 2, 255)), 0)
        notes.addNote("Short", listOf(NoteRange(2, 256, 2, 256)), 1)
        collections.addCollection(SECRET_COLLECTION, 2)

        client.signedIn()
        val run = next()

        assertEquals(listOf(run), stored())
        val text = storedText()
        assertTrue("SIGN_IN|FAILED|6|0|0|1|SERVER|503|ServiceUnavailableError" in text, "the dump reads the row: $text")
        listOf(SECRET_NOTE, SECRET_COLLECTION, "Short", "Could not save").forEach { assertFalse(it in text, "\"$it\" in $text") }
    }

    /**
     * syncStatus shows the wait for the retry, with when it comes (the jittered delay the scheduler waits); a "sync now"
     * runs at once in its place.
     */
    @Test
    fun `a sync now that replaces a waiting retry cancels that run`() {
        retryBase = 1.hours
        retryJitter = 0.5
        jitterSample = 0.9
        server.faults.failRequests(times = 1, status = 503, code = "ServiceUnavailableError")
        notes.addNote("A note pushed by the sync now", listOf(NoteRange(2, 255, 2, 255)), 0)
        client.triggerSyncImmediately()
        val retrying = runBlocking { withTimeout(15.seconds) { status.status.first { it is SyncStatus.Retrying } } }
        assertIs<SyncStatus.Retrying>(retrying)
        assertEquals(503 to "ServiceUnavailableError", retrying.error.httpStatus to retrying.error.serverCode)
        assertEquals(84.minutes, retrying.nextAttemptAt - retrying.failedAt, "the first retry's backoff, 1.4 h by its jitter")
        assertTrue(ended.tryReceive().isFailure, "the run is still open")

        client.triggerSyncImmediately()
        val first = next()
        val second = next()

        assertEquals(listOf(SyncRunResult.CANCELLED, SyncRunResult.SUCCEEDED), listOf(first.result, second.result))
        assertEquals(SyncStatus.Succeeded(second.endedAt), status.status.value)
        assertEquals(1 to 0, first.attempts to first.pushed, "the failed attempt")
        assertEquals(SyncRunTrigger.MANUAL, second.trigger)
        assertEquals(listOf(second, first), stored())
    }

    /**
     * "Try again" during the wait shows syncing at once, while the new run still waits for its headers (a token
     * refresh, say), not the wait for a retry it replaced.
     */
    @Test
    fun `a sync now that replaces a waiting retry shows syncing before its headers`() {
        retryBase = 1.hours
        server.faults.failRequests(times = 1, status = 503, code = "ServiceUnavailableError")
        notes.addNote("A note whose first push fails", listOf(NoteRange(2, 255, 2, 255)), 0)
        client.triggerSyncImmediately()
        runBlocking { withTimeout(15.seconds) { status.status.first { it is SyncStatus.Retrying } } }
        val gate = CompletableDeferred<Unit>()
        headersGate = gate
        val asked = headerRequests.get()

        client.triggerSyncImmediately()

        runBlocking { withTimeout(15.seconds) { while (headerRequests.get() == asked) delay(5) } }
        assertEquals(SyncStatus.Syncing, status.status.value, "the new run is waiting for its headers")
        gate.complete(Unit)
        assertEquals(listOf(SyncRunResult.CANCELLED, SyncRunResult.SUCCEEDED), listOf(next().result, next().result))
    }

    /** Signed out, a return to the foreground asks for headers once, gets none, and ends: no request, no record. */
    @Test
    fun `a return to the foreground while signed out sends nothing and records nothing`() {
        foregroundDelay = 5.milliseconds
        headersLeft = 0
        notes.addNote("A note written signed out", listOf(NoteRange(2, 255, 2, 255)), 0)

        client.appForegrounded()
        awaitSkip()

        assertEquals(1, headerRequests.get())
        assertEquals(emptyList(), server.requests("phone"))
        assertEquals(emptyList(), stored())
        assertTrue(ended.tryReceive().isFailure, "nothing ended")
        assertEquals(SyncStatus.Idle, status.status.value)
    }

    @Test
    fun `closing the client during a run records it cancelled`() {
        val gate = server.faults.blockPost()
        notes.addNote("A note whose POST never answers", listOf(NoteRange(2, 255, 2, 255)), 0)
        client.triggerSyncImmediately()
        gate.awaitArrived()

        client.close()
        gate.release()

        val run = next()
        assertEquals(SyncRunResult.CANCELLED to 1, run.result to run.attempts)
        assertEquals(listOf(run), stored())
    }

    /**
     * The session ends while a retry waits: the retry finds no session. The run reached the server, so it is recorded
     * cancelled with what it did; both attempts count. syncStatus goes back to what it was, as for any skipped run.
     */
    @Test
    fun `a retry that finds no session records the run cancelled with its attempts and counts`() {
        failSecondPost(503, "ServiceUnavailableError") { headersLeft = 0 }

        client.triggerSyncImmediately()
        val run = next()

        assertEquals(SyncRunResult.CANCELLED to SyncRunTrigger.MANUAL, run.result to run.trigger)
        assertEquals(listOf(2, 100, 0), listOf(run.attempts, run.pushed, run.pulled), "attempts, pushed, pulled")
        assertNull(run.error)
        assertEquals(listOf(run), stored())
        awaitSkip()
        assertEquals(SyncStatus.Idle, status.status.value, "as before the run")
    }

    @Test
    fun `a refused token refresh after pushes records the run cancelled`() {
        refreshes = false
        failSecondPost(401, "TokenExpiredError")

        client.triggerSyncImmediately()
        val run = next()

        assertEquals(SyncRunResult.CANCELLED, run.result)
        assertEquals(listOf(1, 100), listOf(run.attempts, run.pushed), "attempts, pushed")
        assertEquals(listOf(run), stored())
        awaitSkip()
        assertEquals(SyncStatus.Idle, status.status.value)
    }

    /** The session ended between the attempt's check and its first request: nothing reached the server. */
    @Test
    fun `a run whose session ends before its first request is not recorded`() {
        headersLeft = 1
        notes.addNote("A note that never leaves", listOf(NoteRange(2, 255, 2, 255)), 0)

        client.triggerSyncImmediately()
        awaitSkip()

        assertEquals(emptyList(), server.requests("phone"))
        assertEquals(emptyList(), stored())
        assertTrue(ended.tryReceive().isFailure, "nothing ended")
        assertEquals(SyncStatus.Idle, status.status.value)
    }

    private companion object {
        const val ACCOUNT = "alice"
        const val COLUMNS = 13
        const val SECRET_NOTE = "A private reflection on mercy"
        const val SECRET_COLLECTION = "My private study circle"
    }
}
