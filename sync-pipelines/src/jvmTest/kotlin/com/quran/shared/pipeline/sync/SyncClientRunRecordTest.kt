package com.quran.shared.pipeline.sync

import app.cash.sqldelight.db.QueryResult
import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.pipeline.SyncHistory
import com.quran.shared.pipeline.SyncRun
import com.quran.shared.pipeline.SyncRunResult
import com.quran.shared.pipeline.SyncRunTrigger
import com.quran.shared.pipeline.SynchronizationEnvironment
import com.quran.shared.pipeline.inMemoryMobileSyncDatabase
import com.quran.shared.pipeline.integration.server.FakeSyncServer
import com.quran.shared.pipeline.integration.server.ServerFaults
import com.quran.shared.syncv2.types.CollectionsRepository
import com.quran.shared.syncv2.types.NoteRange
import com.quran.shared.syncv2.types.NotesRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [SyncClient] with the real engine over an in-memory database against the integration [FakeSyncServer]: what it
 * records per run in [SyncHistory], read back from `sync_run` itself.
 */
class SyncClientRunRecordTest {
    private val server = FakeSyncServer()
    private val database: MobileSyncDatabase = inMemoryMobileSyncDatabase()
    private val history = SyncHistory(database.synced)
    private val ended = Channel<SyncRun>(Channel.UNLIMITED)
    private val token = server.issueToken(ACCOUNT, "phone")
    private val notes = NotesRepository(database.synced)
    private val collections = CollectionsRepository(database.synced)
    private var retryBase: Duration = 5.milliseconds
    private var localChangeDelay: Duration = 1.hours
    private val client by lazy {
        val account = object : SyncAccount {
            override suspend fun authHeaders() = mapOf("Authorization" to "Bearer $token")
            override suspend fun accountId() = ACCOUNT
            override suspend fun forceTokenRefresh() = true
        }
        val observer = SyncRunObservers(history, object : SyncRunObserver {
            override fun syncEnded(run: SyncRun) {
                ended.trySend(run)
            }
        })
        val timings = SchedulerTimings(1.hours, 1.hours, localChangeDelay, FailureRetryingConfig(retryBase, 2.5, 5))
        SyncClient.create(database.synced, SynchronizationEnvironment(server.url), account, object : SyncLifecycleGate {}, observer, timings)
    }

    @AfterTest
    fun tearDown() {
        client.close()
        server.close()
        database.driver.close()
    }

    private fun next(): SyncRun = runBlocking { withTimeout(15.seconds) { ended.receive() } }

    private fun stored(): List<SyncRun> = runBlocking { history.runs.first() }

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

    @Test
    fun `a failing run records its status and code once, and no user text`() {
        server.faults.failRequests(
            times = ServerFaults.ALWAYS, status = 503, code = "ServiceUnavailableError",
            message = "Could not save \"$SECRET_NOTE\" in \"$SECRET_COLLECTION\""
        )
        notes.addNote(SECRET_NOTE, listOf(NoteRange(2, 255, 2, 255)), 0)
        notes.addNote("Short", listOf(NoteRange(2, 256, 2, 256)), 1)
        collections.addCollection(SECRET_COLLECTION, 2)

        client.signedIn()
        val run = next()

        assertEquals(SyncRunResult.FAILED, run.result)
        assertEquals(SyncRunTrigger.SIGN_IN, run.trigger)
        assertEquals(6, run.attempts, "the first attempt and five retries")
        assertEquals(503 to "ServiceUnavailableError", run.error?.httpStatus to run.error?.serverCode)
        assertEquals(1, run.held, "the short note")
        assertEquals(listOf(run), stored())
        val text = storedText()
        assertTrue("SIGN_IN|FAILED|6|0|0|1|SERVER|503|ServiceUnavailableError" in text, "the dump reads the row: $text")
        listOf(SECRET_NOTE, SECRET_COLLECTION, "Short", "Could not save").forEach { assertFalse(it in text, "\"$it\" in $text") }
    }

    @Test
    fun `a run during a push backoff pulls only and the next sync now pushes`() {
        localChangeDelay = 10.milliseconds
        server.faults.failRequests(times = 1, status = 403, code = "ForbiddenError")
        notes.addNote("A note pushed after the backoff", listOf(NoteRange(2, 255, 2, 255)), 0)
        client.triggerSyncImmediately()
        assertEquals(SyncRunResult.FAILED, next().result, "a lone 403 backs pushes off")
        server.web(ACCOUNT).createNote("A note from the web", "1:1-1:1")

        client.localDataUpdated()
        val pulled = next()

        assertEquals(SyncRunResult.PULLED_ONLY to 0, pulled.result to pulled.pushed)
        assertEquals(1, pulled.pulled)
        client.triggerSyncImmediately()
        assertEquals(SyncRunResult.SUCCEEDED to 1, next().let { it.result to it.pushed })
    }

    @Test
    fun `a sync now that replaces a waiting retry cancels that run`() {
        retryBase = 1.hours
        server.faults.failRequests(times = 1, status = 503, code = "ServiceUnavailableError")
        notes.addNote("A note pushed by the sync now", listOf(NoteRange(2, 255, 2, 255)), 0)
        client.triggerSyncImmediately()
        // The second "sync now" must come after the first attempt reached the server, or the two are one run.
        runBlocking { withTimeout(15.seconds) { while (server.requests("phone").none { it.method == "POST" }) delay(5) } }
        client.triggerSyncImmediately()
        val first = next()
        val second = next()

        assertEquals(listOf(SyncRunResult.CANCELLED, SyncRunResult.SUCCEEDED), listOf(first.result, second.result))
        assertEquals(1 to 0, first.attempts to first.pushed, "the failed attempt")
        assertEquals(SyncRunTrigger.MANUAL, second.trigger)
        assertEquals(listOf(second, first), stored())
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

    private companion object {
        const val ACCOUNT = "alice"
        const val COLUMNS = 13
        const val SECRET_NOTE = "A private reflection on mercy"
        const val SECRET_COLLECTION = "My private study circle"
    }
}
