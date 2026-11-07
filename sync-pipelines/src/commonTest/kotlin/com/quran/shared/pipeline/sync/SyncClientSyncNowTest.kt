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
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** [SyncClient.syncNow] with the real engine against a mock server, on virtual time. */
class SyncClientSyncNowTest {
    private val database = inMemoryMobileSyncDatabase()
    private val gate = ResettableGate()
    private val ended = mutableListOf<SyncRun>()
    private var signedIn = true
    private var status = HttpStatusCode.OK
    private var requests = 0

    /** When set, the next GET waits for it. */
    private var hold: CompletableDeferred<Unit>? = null

    @AfterTest
    fun tearDown() {
        database.driver.close()
    }

    /** Retries wait 10 s, then 20 s. */
    private fun syncTest(maximumRetries: Int = 2, test: suspend TestScope.(SyncClient) -> Unit): TestResult = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = MockEngine(
            MockEngineConfig().apply {
                this.dispatcher = dispatcher
                addHandler {
                    requests++
                    hold?.also { hold = null }?.await()
                    respond(EMPTY_PAGE, status, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        )
        val account = object : SyncAccount {
            override suspend fun authHeaders() = if (signedIn) mapOf("Authorization" to "Bearer token") else emptyMap()
            override suspend fun accountId() = "alice"
            override suspend fun forceTokenRefresh() = false
        }
        val observer = object : SyncRunObserver {
            override fun syncEnded(run: SyncRun) {
                ended += run
            }
        }
        val timings = SchedulerTimings(
            1.hours, 1.hours, 1.hours, 1.hours, Duration.ZERO,
            FailureRetryingConfig(baseDelay = 10.seconds, multiplier = 2.0, maximumRetries = maximumRetries)
        )
        val environment = SynchronizationEnvironment("https://sync.example.invalid", avatarsHost = "https://avatars.example.invalid")
        val client = SyncClient.create(
            database.synced, environment, account, gate, observer, timings, random = { 0.5 }, engine = engine,
            dispatcher = dispatcher, now = { testScheduler.currentTime }
        )
        try {
            test(client)
        } finally {
            client.closeAndJoin()
        }
    }

    @Test
    fun `a sync now while idle returns the run once it succeeds`() = syncTest { client ->
        val run = assertIs<SyncNowResult.Ended>(client.syncNow()).run

        assertEquals(SyncRunResult.SUCCEEDED to SyncRunTrigger.MANUAL, run.result to run.trigger)
        assertEquals(listOf(run), ended)
    }

    @Test
    fun `a sync now whose attempt fails returns while the run waits to retry`() = syncTest { client ->
        status = HttpStatusCode.ServiceUnavailable

        val retrying = assertIs<SyncNowResult.Retrying>(client.syncNow())

        assertEquals(503, retrying.error.httpStatus)
        assertEquals(10.seconds, retrying.nextAttemptAt.fromPlatform() - retrying.failedAt.fromPlatform())
        assertEquals(0L, currentTime, "not after the backoff")
        assertEquals(emptyList(), ended)
    }

    @Test
    fun `a sync now whose run fails for good returns the failed run`() = syncTest(maximumRetries = 0) { client ->
        status = HttpStatusCode.ServiceUnavailable

        val run = assertIs<SyncNowResult.Ended>(client.syncNow()).run

        assertEquals(SyncRunResult.FAILED to 503, run.result to run.error?.httpStatus)
    }

    @Test
    fun `a sync now while a run waits to retry replaces the retry`() = syncTest { client ->
        status = HttpStatusCode.ServiceUnavailable
        assertIs<SyncNowResult.Retrying>(client.syncNow())
        status = HttpStatusCode.OK

        assertEquals(SyncRunResult.SUCCEEDED, assertIs<SyncNowResult.Ended>(client.syncNow()).run.result)
        assertEquals(listOf(SyncRunResult.CANCELLED, SyncRunResult.SUCCEEDED), ended.map { it.result })
    }

    @Test
    fun `a sync now while signed out returns at once without a request`() = syncTest { client ->
        signedIn = false

        assertEquals(SyncNowResult.SignedOut, client.syncNow())
        assertEquals(0, requests)
    }

    @Test
    fun `cancelling a sync now's caller doesn't cancel the sync`() = syncTest { client ->
        val answer = CompletableDeferred<Unit>().also { hold = it }
        val waited = launch { client.syncNow() }
        runCurrent()

        waited.cancel()
        runCurrent()
        answer.complete(Unit)
        runCurrent()

        assertEquals(listOf(SyncRunResult.SUCCEEDED), ended.map { it.result })
    }

    /** As QuranDataService's logout does: the epoch moves on and the running sync is cancelled. */
    @Test
    fun `a logout during the run ends the wait`() = syncTest { client ->
        hold = CompletableDeferred()
        val waited = async { client.syncNow() }
        runCurrent()

        gate.beginReset()
        client.cancelSyncingAndJoin()

        assertEquals(SyncNowResult.Unavailable, waited.await())
        assertEquals(emptyList(), ended)
    }

    @Test
    fun `a logout that begins during the run's request ends the wait`() = syncTest { client ->
        val answer = CompletableDeferred<Unit>().also { hold = it }
        val waited = async { client.syncNow() }
        runCurrent()

        gate.beginReset()
        answer.complete(Unit)

        assertEquals(SyncNowResult.Unavailable, waited.await())
    }

    @Test
    fun `closing the client during the run returns the cancelled run`() = syncTest { client ->
        hold = CompletableDeferred()
        val waited = async { client.syncNow() }
        runCurrent()

        client.close()

        assertEquals(SyncRunResult.CANCELLED, assertIs<SyncNowResult.Ended>(waited.await()).run.result)
    }

    private companion object {
        const val EMPTY_PAGE = """{"data":{"lastMutationAt":1,"mutations":[]}}"""
    }
}

private class ResettableGate : SyncLifecycleGate {
    private var resetting = false

    override fun canStartSync() = !resetting

    override suspend fun captureSyncEpoch(): Long = 0L.also { checkSyncEpoch(it) }

    override suspend fun checkSyncEpoch(epoch: Long) {
        if (resetting) throw SyncOperationInvalidatedException("A reset began")
    }

    fun beginReset() {
        resetting = true
    }
}
