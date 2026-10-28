@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.quran.shared.pipeline

import app.cash.sqldelight.db.SqlDriver
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.LogoutTokenMaterial
import com.quran.shared.auth.repository.RemoteLogoutFailure
import com.quran.shared.auth.repository.RemoteLogoutMode
import com.quran.shared.auth.service.AuthService
import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.repository.PersistenceResetRepositoryImpl
import com.quran.shared.pipeline.sync.SyncClient
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepositoryImpl
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.repository.note.repository.NotesRepositoryImpl
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepositoryImpl
import com.quran.shared.pipeline.sync.SyncRunObserver
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createDynamicGraphFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * Runs [QuranDataService] with the real sync engine and database, signed out so no request leaves
 * the process.
 */
class QuranDataServiceSyncStatusTest {
    private val dispatcher = StandardTestDispatcher()
    private val events = SyncRunEvents()
    private lateinit var driver: SqlDriver
    private lateinit var authService: AuthService
    private lateinit var service: QuranDataService

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val database = inMemoryMobileSyncDatabase()
        driver = database.driver
        val synced = database.synced
        authService = AuthService(SignedOutAuthRepository())
        val pipeline = SyncEnginePipeline(
            bookmarksRepository = BookmarksRepositoryImpl(synced),
            readingBookmarksRepository = ReadingBookmarksRepositoryImpl(synced),
            collectionsRepository = CollectionsRepositoryImpl(synced),
            collectionBookmarksRepository = CollectionBookmarksRepositoryImpl(synced),
            notesRepository = NotesRepositoryImpl(synced),
            readingSessionsRepository = ReadingSessionsRepositoryImpl(synced)
        )
        service = createDynamicGraphFactory<QuranDataServiceTestGraph.Factory>(RecordingSyncBindings(events, database))
            .create(
                authService = authService,
                pipeline = pipeline,
                environment = SynchronizationEnvironment("https://example.invalid"),
                persistenceResetRepository = PersistenceResetRepositoryImpl(synced),
                persistenceImportRepository = PersistenceImportRepositoryImpl(database.quran, synced),
                sessionLifecycleCoordinator = SessionLifecycleCoordinator(
                    SettingsSessionLifecycleStateStore(MapSettings().toSuspendSettings())
                ),
                database = synced
            ).quranDataService
    }

    @AfterTest
    fun tearDown() {
        runTest(dispatcher) {
            service.clearAndJoin()
            authService.clearAndJoin()
        }
        driver.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `sync run outcomes drive the status`() = runTest(dispatcher) {
        service.awaitInitialization()

        events.syncStarted()
        assertEquals(SyncStatus.Syncing, service.syncStatus.value)
        events.syncEnded(run(SyncRunResult.SUCCEEDED))
        assertEquals(SyncStatus.Succeeded(endedAt), service.syncStatus.value, "the run's end")
        events.syncStarted()
        events.syncEnded(run(SyncRunResult.FAILED, serverError))
        assertEquals(serverError, assertIs<SyncStatus.Failed>(service.syncStatus.value).error)
        events.syncStarted()
        events.syncEnded(run(SyncRunResult.PULLED_ONLY))
        assertEquals(serverError, assertIs<SyncStatus.Failed>(service.syncStatus.value).error, "a pull keeps the failure")
    }

    @Test
    fun `a failed attempt that will be retried shows retrying until the retry runs`() = runTest(dispatcher) {
        service.awaitInitialization()

        events.syncStarted()
        events.syncRetrying(failedAt, serverError, retryAt)
        assertEquals(SyncStatus.Retrying(failedAt, serverError, retryAt), service.syncStatus.value)
        events.syncStarted()
        assertEquals(SyncStatus.Syncing, service.syncStatus.value, "the retry runs")
        events.syncRetrying(failedAt, networkError, retryAt)
        assertEquals(networkError, assertIs<SyncStatus.Retrying>(service.syncStatus.value).error, "the latest attempt's error")
        events.syncStarted()
        events.syncEnded(run(SyncRunResult.FAILED, serverError))
        assertEquals(SyncStatus.Failed(endedAt, serverError), service.syncStatus.value, "the retries ran out")
    }

    /** A "sync now" replaces the waiting retry: the run ends cancelled and the new one reports syncing. */
    @Test
    fun `a run whose retry a sync now replaced stays retrying until the new run starts`() = runTest(dispatcher) {
        service.awaitInitialization()
        events.syncStarted()
        events.syncRetrying(failedAt, networkError, retryAt)

        events.syncEnded(run(SyncRunResult.CANCELLED))
        assertIs<SyncStatus.Retrying>(service.syncStatus.value)
        events.syncStarted()
        assertEquals(SyncStatus.Syncing, service.syncStatus.value)
        events.syncEnded(run(SyncRunResult.SUCCEEDED))
        assertEquals(SyncStatus.Succeeded(endedAt), service.syncStatus.value)
    }

    @Test
    fun `a retry that ends without a result restores the result from before the run`() = runTest(dispatcher) {
        service.awaitInitialization()
        events.syncEnded(run(SyncRunResult.SUCCEEDED))
        events.syncStarted()
        events.syncRetrying(failedAt, networkError, retryAt)

        events.syncStarted()
        events.syncSkipped()

        assertEquals(SyncStatus.Succeeded(endedAt), service.syncStatus.value)
    }

    /** A pull during a push backoff that needed a retry still ends on the failure that started the backoff. */
    @Test
    fun `a pulled-only run after a retry keeps the failure`() = runTest(dispatcher) {
        service.awaitInitialization()
        events.syncEnded(run(SyncRunResult.FAILED, serverError))
        events.syncStarted()
        events.syncRetrying(failedAt, networkError, retryAt)
        events.syncStarted()

        events.syncEnded(run(SyncRunResult.PULLED_ONLY))

        assertEquals(SyncStatus.Failed(endedAt, serverError), service.syncStatus.value)
    }

    @Test
    fun `skipped run restores the result from before it started`() = runTest(dispatcher) {
        service.awaitInitialization()
        events.syncEnded(run(SyncRunResult.FAILED, serverError))

        events.syncStarted()
        events.syncSkipped()

        assertEquals(serverError, assertIs<SyncStatus.Failed>(service.syncStatus.value).error)
    }

    @Test
    fun `run without a session keeps the latest result`() = runTest(dispatcher) {
        service.awaitInitialization()
        events.syncEnded(run(SyncRunResult.FAILED, serverError))
        events.next()

        service.triggerSync(immediately = true)

        assertEquals("skipped", events.next())
        assertEquals(serverError, assertIs<SyncStatus.Failed>(service.syncStatus.value).error)
    }

    @Test
    fun `logout clears the latest sync result`() = runTest(dispatcher) {
        service.awaitInitialization()
        events.syncEnded(run(SyncRunResult.FAILED, serverError))

        service.logout()

        assertEquals(SyncStatus.Idle, service.syncStatus.value)
    }
}

private val endedAt = Instant.fromEpochMilliseconds(1_760_000_000_000L)
private val failedAt = Instant.fromEpochMilliseconds(1_759_999_999_000L)
private val retryAt = Instant.fromEpochMilliseconds(1_760_000_000_500L)

private fun run(result: SyncRunResult, error: SyncError? = null) = SyncRun(
    startedAt = endedAt, endedAt = endedAt, trigger = SyncRunTrigger.MANUAL, result = result,
    attempts = 1, pushed = 0, pulled = 0, held = 0, error = error
)

private val serverError = SyncError(
    kind = SyncErrorKind.SERVER,
    httpStatus = 503,
    serverCode = "ServerError",
    message = "The sync server is unavailable (HTTP 503)"
)

private val networkError = SyncError(SyncErrorKind.NETWORK, null, null, "Could not reach the sync server")

@BindingContainer
private class RecordingSyncBindings(private val events: SyncRunEvents, private val database: MobileSyncDatabase) {
    @Provides
    fun synchronizationClientFactory(): QuranDataServiceSynchronizationClientFactory =
        QuranDataServiceSynchronizationClientFactory { environment, account, gate, observer ->
            events.service = observer
            SyncClient.create(database.synced, environment, account, gate, events)
        }
}

/** Records sync run events and forwards them to the service's own observer. */
private class SyncRunEvents : SyncRunObserver {
    lateinit var service: SyncRunObserver
    private val recorded = Channel<String>(Channel.UNLIMITED)

    override fun syncStarted() = record("started") { service.syncStarted() }
    override fun syncRetrying(failedAt: PlatformDateTime, error: SyncError, nextAttemptAt: PlatformDateTime) =
        record("retrying") { service.syncRetrying(failedAt, error, nextAttemptAt) }
    override fun syncSkipped() = record("skipped") { service.syncSkipped() }
    override fun syncEnded(run: SyncRun) = record(run.result.name) { service.syncEnded(run) }

    suspend fun next(): String =
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { recorded.receive() }
        }

    private fun record(event: String, forward: () -> Unit) {
        forward()
        recorded.trySend(event)
    }
}

private class SignedOutAuthRepository : AuthRepository {
    override suspend fun login(): Unit = error("Login is not used by sync status tests")
    override suspend fun loginWithReauthentication(): Unit = error("Login is not used by sync status tests")
    override suspend fun refreshTokensIfNeeded() = false
    override suspend fun logout() = Unit
    override suspend fun captureLogoutTokenMaterial() = LogoutTokenMaterial(null, null)
    override suspend fun clearLocalSession() = Unit
    override suspend fun attemptRemoteLogout(
        tokenMaterial: LogoutTokenMaterial,
        mode: RemoteLogoutMode
    ) = emptyList<RemoteLogoutFailure>()
    override suspend fun getAccessToken(): String? = null
    override suspend fun isLoggedIn() = false
    override suspend fun getCurrentUser(): UserInfo? = null
    override suspend fun getAuthHeaders() = emptyMap<String, String>()
}
