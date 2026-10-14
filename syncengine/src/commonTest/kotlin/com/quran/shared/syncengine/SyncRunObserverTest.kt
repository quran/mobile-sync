package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncBookmark
import com.quran.shared.syncengine.scheduling.DefaultTimings
import com.quran.shared.syncengine.scheduling.FailureRetryingConfig
import com.quran.shared.syncengine.scheduling.SchedulerTimings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class SyncRunObserverTest {
    private val backend = SyncBackend()
    private val observer = SyncRunEvents()
    private var client: SynchronizationClientImpl? = null

    @AfterTest
    fun tearDown() = runTest {
        client?.cancelSyncingAndJoin()
    }

    @Test
    fun `immediate trigger syncs without waiting and reports success`() = runTest {
        val client = createClient(timings = DefaultTimings)

        client.triggerSyncImmediately()

        assertEquals(listOf("started", "succeeded"), observer.await(2))
        assertEquals(1, backend.requestCount)
    }

    @Test
    fun `failing run reports started for every attempt and a single failure`() = runTest {
        backend.status = { HttpStatusCode.ServiceUnavailable }
        val client = createClient()

        client.triggerSyncImmediately()

        assertEquals(
            listOf("started", "started", "started", "failed SERVER 503 ServerError"),
            observer.await(4)
        )
        assertEquals(3, backend.requestCount)
    }

    @Test
    fun `failure of a run that outlived a session reset is dropped`() = runTest {
        val gate = ResettableSyncLifecycleGate()
        backend.status = {
            gate.reset()
            HttpStatusCode.ServiceUnavailable
        }
        val client = createClient(timings = FastTimings.withMaximumRetries(0), syncLifecycleGate = gate)

        client.triggerSyncImmediately()

        assertEquals(listOf("started", "skipped"), observer.await(2))
    }

    @Test
    fun `retry that succeeds reports success without failure`() = runTest {
        backend.status = { index -> if (index == 0) HttpStatusCode.InternalServerError else HttpStatusCode.OK }
        val client = createClient()

        client.triggerSyncImmediately()

        assertEquals(listOf("started", "started", "succeeded"), observer.await(3))
    }

    @Test
    fun `run without a session is skipped without contacting the server`() = runTest {
        val client = createClient(authHeaders = emptyMap())

        client.triggerSyncImmediately()

        assertEquals(listOf("skipped"), observer.await(1))
        assertEquals(0, backend.requestCount)
    }

    @Test
    fun `run invalidated by a reset is skipped without retrying`() = runTest {
        val client = createClient(
            syncLifecycleGate = object : SyncLifecycleGate {
                override suspend fun checkSyncEpoch(epoch: Long) {
                    throw SyncOperationInvalidatedException("Session reset")
                }
            }
        )

        client.triggerSyncImmediately()

        assertEquals(listOf("started", "skipped"), observer.await(2))
        assertEquals(1, backend.requestCount)
    }

    private fun createClient(
        timings: SchedulerTimings = FastTimings,
        authHeaders: Map<String, String> = mapOf("Authorization" to "Bearer token"),
        syncLifecycleGate: SyncLifecycleGate = object : SyncLifecycleGate {}
    ): SynchronizationClientImpl =
        SynchronizationClientImpl(
            environment = SynchronizationEnvironment("https://example.test"),
            httpClient = backend.httpClient,
            resourceAdapters = listOf(BookmarksSyncAdapter(EmptyBookmarksConfigurations)),
            authenticationDataFetcher = object : AuthenticationDataFetcher {
                override suspend fun fetchAuthenticationHeaders(): Map<String, String> = authHeaders
                override fun isLoggedIn(): Boolean = authHeaders.isNotEmpty()
            },
            syncCompletionFinalizer = SyncCompletionFinalizer { },
            syncLifecycleGate = syncLifecycleGate,
            syncRunObserver = observer,
            schedulerTimings = timings
        ).also { client = it }
}

private val FastTimings = SchedulerTimings(
    standardInterval = 1.hours,
    appRefreshInterval = 1.milliseconds,
    localDataModifiedInterval = 1.milliseconds,
    failureRetryingConfig = FailureRetryingConfig(baseDelay = 1.milliseconds, multiplier = 1.0, maximumRetries = 2)
)

private fun SchedulerTimings.withMaximumRetries(maximumRetries: Int) =
    copy(failureRetryingConfig = failureRetryingConfig.copy(maximumRetries = maximumRetries))

/** Starts a new session epoch on [reset], as a logout does. */
private class ResettableSyncLifecycleGate : SyncLifecycleGate {
    @Volatile
    private var epoch = 0L

    fun reset() {
        epoch += 1
    }

    override suspend fun captureSyncEpoch(): Long = epoch

    override suspend fun checkSyncEpoch(epoch: Long) {
        if (epoch != this.epoch) throw SyncOperationInvalidatedException("Session reset")
    }
}

private class SyncRunEvents : SyncRunObserver {
    private val events = Channel<String>(Channel.UNLIMITED)

    override fun syncStarted() { events.trySend("started") }
    override fun syncSucceeded() { events.trySend("succeeded") }
    override fun syncSkipped() { events.trySend("skipped") }
    override fun syncFailed(error: SyncError) {
        events.trySend("failed ${error.kind} ${error.httpStatus} ${error.serverCode}")
    }

    suspend fun await(count: Int): List<String> =
        withContext(Dispatchers.Default) {
            withTimeout(2_000) { List(count) { events.receive() } }
        }
}

/**
 * Serves an empty `GET /v1/sync` page, or the backend's error body when the chosen status is not OK.
 */
private class SyncBackend {
    var status: (index: Int) -> HttpStatusCode = { HttpStatusCode.OK }
    var requestCount = 0
        private set

    val httpClient = HttpClient(
        MockEngine {
            val status = status(requestCount++)
            respond(
                content = if (status == HttpStatusCode.OK) {
                    """{"success":true,"data":{"lastMutationAt":10,"mutations":[],"total":0}}"""
                } else {
                    """{"success":false,"error":{"code":"ServerError","message":"Something went wrong"}}"""
                },
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
    ) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
}

private val EmptyBookmarksConfigurations = BookmarksSynchronizationConfigurations(
    localDataFetcher = object : LocalDataFetcher<SyncBookmark> {
        override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncBookmark>> =
            emptyList()
        override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
            remoteIDs.associateWith { false }
        override suspend fun fetchLocalModel(remoteId: String): SyncBookmark? = null
    },
    resultNotifier = object : ResultNotifier<SyncBookmark> {
        override suspend fun didSucceed(
            newToken: Long,
            newRemoteMutations: List<RemoteModelMutation<SyncBookmark>>,
            processedLocalMutations: List<LocalModelMutation<SyncBookmark>>
        ) = Unit
        override suspend fun didFail(message: String) = Unit
    },
    localModificationDateFetcher = object : LocalModificationDateFetcher {
        override suspend fun localLastModificationDate(): Long = 0L
    }
)
