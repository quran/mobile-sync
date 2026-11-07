@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.quran.shared.pipeline.sync

import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.pipeline.SyncNowResult
import com.quran.shared.pipeline.SyncRun
import com.quran.shared.pipeline.SyncRunResult
import com.quran.shared.pipeline.SyncRunTrigger
import com.quran.shared.pipeline.SynchronizationEnvironment
import com.quran.shared.pipeline.inMemoryMobileSyncDatabase
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * [SyncClient.syncNow] on virtual time, with the real engine over an in-memory database and a mock sync server: it
 * returns once the run it asked for ended or waits to retry, and never sooner or later.
 */
class SyncClientSyncNowTest {
    private val database = inMemoryMobileSyncDatabase()
    private val gate = ResettableGate()
    private val ended = mutableListOf<SyncRun>()
    private val observer = object : SyncRunObserver {
        override fun syncEnded(run: SyncRun) {
            ended += run
        }
    }

    private var signedIn = true
    private val account = object : SyncAccount {
        override suspend fun authHeaders() = if (signedIn) mapOf("Authorization" to "Bearer token") else emptyMap()
        override suspend fun accountId() = "alice"
        override suspend fun forceTokenRefresh() = false
    }

    /** The status the sync server answers every request with. */
    private var status = HttpStatusCode.OK

    /** Each GET waits for the next of these, when there is one: a server that hasn't answered yet. */
    private val holds = ArrayDeque<CompletableDeferred<Unit>>()

    /** The requests the server received, in order. */
    private val requests = mutableListOf<HttpMethod>()

    @AfterTest
    fun tearDown() {
        database.driver.close()
    }

    /** Runs [test] with a client on virtual time; retries wait 10 s, then 20 s, up to [maximumRetries]. */
    private fun syncTest(maximumRetries: Int = 2, test: suspend TestScope.(SyncClient) -> Unit): TestResult = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = MockEngine(
            MockEngineConfig().apply {
                this.dispatcher = dispatcher
                addHandler { request ->
                    requests += request.method
                    if (request.method == HttpMethod.Get) holds.removeFirstOrNull()?.await()
                    respond(EMPTY_PAGE, status, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        )
        val retrying = FailureRetryingConfig(baseDelay = 10.seconds, multiplier = 2.0, maximumRetries = maximumRetries)
        val timings = SchedulerTimings(1.hours, 1.hours, 1.hours, 1.hours, Duration.ZERO, retrying)
        val environment = SynchronizationEnvironment("https://sync.example.invalid", avatarsHost = "https://avatars.example.invalid")
        val client = SyncClient.create(
            database.synced, environment, account, gate, observer, timings, random = { 0.5 }, engine = engine,
            now = { testScheduler.currentTime }, dispatcher = dispatcher
        )
        try {
            test(client)
        } finally {
            client.closeAndJoin()
        }
    }

    private fun hold() = CompletableDeferred<Unit>().also { holds += it }

    @Test
    fun `a sync now while idle returns the run once it succeeds`() = syncTest { client ->
        val result = client.syncNow()

        val run = assertIs<SyncNowResult.Ended>(result).run
        assertEquals(SyncRunResult.SUCCEEDED to SyncRunTrigger.MANUAL, run.result to run.trigger)
        assertEquals(listOf(run), ended, "the run the history records")
    }

    /** A run in flight may have read the server before the call: the call waits for the run right after it. */
    @Test
    fun `a sync now during a run waits for the run after it`() = syncTest { client ->
        val first = hold()
        val second = hold()
        client.signedIn()
        runCurrent()
        val waited = async { client.syncNow() }
        runCurrent()

        first.complete(Unit)
        runCurrent()
        assertEquals(listOf(SyncRunTrigger.SIGN_IN), ended.map { it.trigger }, "the run in flight ended")
        assertFalse(waited.isCompleted, "the run after it is running")
        second.complete(Unit)

        val run = assertIs<SyncNowResult.Ended>(waited.await()).run
        assertEquals(SyncRunTrigger.MANUAL to SyncRunResult.SUCCEEDED, run.trigger to run.result)
        assertEquals(ended.last(), run)
    }

    @Test
    fun `sync now calls before the run starts share it`() = syncTest { client ->
        val first = async { client.syncNow() }
        val second = async { client.syncNow() }

        assertEquals(first.await(), second.await())
        assertEquals(1, ended.size, "one run")
    }

    /** Offline or with the server down, the caller doesn't wait through the backoff. */
    @Test
    fun `a sync now whose attempt fails returns while the run waits to retry`() = syncTest { client ->
        status = HttpStatusCode.ServiceUnavailable

        val result = client.syncNow()

        val retrying = assertIs<SyncNowResult.Retrying>(result)
        assertEquals(503, retrying.error.httpStatus)
        assertEquals(0L, currentTime, "at once, not after the backoff")
        val wait = retrying.nextAttemptAt.fromPlatform() - retrying.failedAt.fromPlatform()
        assertEquals(10.seconds, wait, "the first retry's backoff")
        assertEquals(emptyList(), ended, "the run is still open")
    }

    @Test
    fun `a sync now whose run fails for good returns the failed run`() = syncTest(maximumRetries = 0) { client ->
        status = HttpStatusCode.ServiceUnavailable

        val result = client.syncNow()

        val run = assertIs<SyncNowResult.Ended>(result).run
        assertEquals(SyncRunResult.FAILED to 503, run.result to run.error?.httpStatus)
        assertEquals(listOf(run), ended)
    }

    /** "Try again" during the wait runs at once: the waiting run ends cancelled and the call gets the new one. */
    @Test
    fun `a sync now while a run waits to retry replaces the retry`() = syncTest { client ->
        status = HttpStatusCode.ServiceUnavailable
        assertIs<SyncNowResult.Retrying>(client.syncNow())
        status = HttpStatusCode.OK

        val result = client.syncNow()

        assertEquals(SyncRunResult.SUCCEEDED, assertIs<SyncNowResult.Ended>(result).run.result)
        assertEquals(listOf(SyncRunResult.CANCELLED, SyncRunResult.SUCCEEDED), ended.map { it.result })
        assertEquals(0L, currentTime, "before the retry was due")
    }

    @Test
    fun `a sync now while signed out returns at once without a request`() = syncTest { client ->
        signedIn = false

        val result = client.syncNow()

        assertEquals(SyncNowResult.SignedOut, result)
        assertEquals(emptyList(), requests)
        assertEquals(emptyList(), ended, "nothing is recorded")
    }

    @Test
    fun `a sync now during a reset returns at once without a run`() = syncTest { client ->
        gate.beginReset()

        val result = client.syncNow()

        assertEquals(SyncNowResult.Unavailable, result)
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `cancelling a sync now's caller doesn't cancel the sync`() = syncTest { client ->
        val answer = hold()
        val waited = launch { client.syncNow() }
        runCurrent()

        waited.cancel()
        runCurrent()
        answer.complete(Unit)
        runCurrent()

        assertEquals(listOf(SyncRunResult.SUCCEEDED), ended.map { it.result }, "the run went on to its end")
    }

    /** A logout cancels the run as QuranDataService's does: it isn't recorded, and the wait ends. */
    @Test
    fun `a logout during the run ends the wait`() = syncTest { client ->
        hold()
        val waited = async { client.syncNow() }
        runCurrent()

        gate.beginReset()
        client.cancelSyncingAndJoin()

        assertEquals(SyncNowResult.Unavailable, waited.await())
        assertEquals(emptyList(), ended)
    }

    /** A logout that begins while the server answers: the run's result is wiped with the data, so it isn't returned. */
    @Test
    fun `a logout that begins during the run's request ends the wait`() = syncTest { client ->
        val answer = hold()
        val waited = async { client.syncNow() }
        runCurrent()

        gate.beginReset()
        answer.complete(Unit)

        assertEquals(SyncNowResult.Unavailable, waited.await())
        assertEquals(emptyList(), ended)
    }

    /** Clearing the service during the run records it cancelled, and the call returns that run. */
    @Test
    fun `closing the client during the run returns the cancelled run`() = syncTest { client ->
        hold()
        val waited = async { client.syncNow() }
        runCurrent()

        client.close()

        val run = assertIs<SyncNowResult.Ended>(waited.await()).run
        assertEquals(SyncRunResult.CANCELLED, run.result)
        assertEquals(listOf(run), ended)
    }

    private companion object {
        /** An empty GET page, and a POST's answer, at one head. */
        const val EMPTY_PAGE = """{"data":{"lastMutationAt":1,"mutations":[]}}"""
    }
}

/** A sync lifecycle a test resets: a logout moves the epoch on and closes the lifecycle while it runs. */
private class ResettableGate : SyncLifecycleGate {
    private var epoch = 0L
    private var resetting = false

    override fun canStartSync() = !resetting

    override suspend fun captureSyncEpoch(): Long {
        if (resetting) throw SyncOperationInvalidatedException("A reset is running")
        return epoch
    }

    override suspend fun checkSyncEpoch(epoch: Long) {
        if (resetting || epoch != this.epoch) throw SyncOperationInvalidatedException("A reset began")
    }

    fun beginReset() {
        resetting = true
        epoch += 1
    }
}
