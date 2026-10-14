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
import com.quran.shared.persistence.repository.PersistenceResetRepositoryImpl
import com.quran.shared.pipeline.sync.SyncClient
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepositoryImpl
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.repository.note.repository.NotesRepositoryImpl
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepositoryImpl
import com.quran.shared.syncengine.SyncError
import com.quran.shared.syncengine.SyncErrorKind
import com.quran.shared.syncengine.SyncRunObserver
import com.quran.shared.syncengine.SynchronizationEnvironment
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
                persistenceResetRepository = PersistenceResetRepositoryImpl(database.quran, synced),
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
        events.syncSucceeded()
        assertIs<SyncStatus.Succeeded>(service.syncStatus.value)
        events.syncStarted()
        events.syncFailed(serverError)
        assertEquals(serverError, assertIs<SyncStatus.Failed>(service.syncStatus.value).error)
    }

    @Test
    fun `skipped run restores the result from before it started`() = runTest(dispatcher) {
        service.awaitInitialization()
        events.syncFailed(serverError)

        events.syncStarted()
        events.syncSkipped()

        assertEquals(serverError, assertIs<SyncStatus.Failed>(service.syncStatus.value).error)
    }

    @Test
    fun `run without a session keeps the latest result`() = runTest(dispatcher) {
        service.awaitInitialization()
        events.syncFailed(serverError)
        events.next()

        service.triggerSync(immediately = true)

        assertEquals("skipped", events.next())
        assertEquals(serverError, assertIs<SyncStatus.Failed>(service.syncStatus.value).error)
    }

    @Test
    fun `logout clears the latest sync result`() = runTest(dispatcher) {
        service.awaitInitialization()
        events.syncFailed(serverError)

        service.logout()

        assertEquals(SyncStatus.Idle, service.syncStatus.value)
    }
}

private val serverError = SyncError(
    kind = SyncErrorKind.SERVER,
    httpStatus = 503,
    serverCode = "ServerError",
    message = "The sync server is unavailable (HTTP 503)"
)

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
    override fun syncSucceeded() = record("succeeded") { service.syncSucceeded() }
    override fun syncSkipped() = record("skipped") { service.syncSkipped() }
    override fun syncFailed(error: SyncError) = record("failed") { service.syncFailed(error) }

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
