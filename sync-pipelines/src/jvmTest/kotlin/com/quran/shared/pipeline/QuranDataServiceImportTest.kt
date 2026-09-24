@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.quran.shared.pipeline

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.LogoutTokenMaterial
import com.quran.shared.auth.repository.RemoteLogoutFailure
import com.quran.shared.auth.repository.RemoteLogoutMode
import com.quran.shared.auth.service.AuthService
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.input.ImportNote
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.repository.PersistenceResetRepositoryImpl
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepositoryImpl
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.repository.note.repository.NotesRepositoryImpl
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepositoryImpl
import com.quran.shared.persistence.util.toPlatform
import com.quran.shared.syncengine.SynchronizationClient
import com.quran.shared.syncengine.SynchronizationEnvironment
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createDynamicGraphFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class QuranDataServiceImportTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: QuranDatabase
    private lateinit var authService: AuthService
    private lateinit var service: QuranDataService
    private lateinit var lifecycleCoordinator: SessionLifecycleCoordinator
    private val syncClient = ImportSyncClientSpy()
    private val data = PersistenceImportData(notes = listOf(
        ImportNote("Imported note", 2, 1, 2, 1, Instant.fromEpochMilliseconds(100).toPlatform())
    ))

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        QuranDatabase.Schema.create(driver)
        database = QuranDatabase(driver)
        authService = AuthService(LoggedOutAuthRepository())
        val readingBookmarks = ReadingBookmarksRepositoryImpl(database)
        lifecycleCoordinator = SessionLifecycleCoordinator(
            SettingsSessionLifecycleStateStore(MapSettings().toSuspendSettings())
        )
        val pipeline = SyncEnginePipeline(
            bookmarksRepository = BookmarksRepositoryImpl(database),
            readingBookmarksRepository = readingBookmarks,
            readingBookmarksSynchronizationRepository = readingBookmarks,
            collectionsRepository = CollectionsRepositoryImpl(database),
            collectionBookmarksRepository = CollectionBookmarksRepositoryImpl(database),
            notesRepository = NotesRepositoryImpl(database),
            readingSessionsRepository = ReadingSessionsRepositoryImpl(database)
        )
        service = createDynamicGraphFactory<QuranDataServiceTestGraph.Factory>(ImportSyncBindings(syncClient))
            .create(
                authService = authService,
                pipeline = pipeline,
                environment = SynchronizationEnvironment("https://example.invalid"),
                persistenceResetRepository = PersistenceResetRepositoryImpl(database),
                persistenceImportRepository = PersistenceImportRepositoryImpl(database),
                syncLocalModificationDateStore = SyncSettingsLocalModificationDateStore(MapSettings().toSuspendSettings()),
                sessionLifecycleCoordinator = lifecycleCoordinator
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
    fun `import schedules sync after inserting user data`() = runTest(dispatcher) {
        val result = service.importData(data, deleteExisting = false, trackHistory = true)

        assertTrue(result.changed)
        assertEquals("Imported note", database.notesQueries.getNotes().executeAsOne().note)
        assertEquals(1, syncClient.localDataUpdatedCount)
    }

    @Test
    fun `recording history for matching data does not schedule sync`() = runTest(dispatcher) {
        PersistenceImportRepositoryImpl(database).importData(data)

        val result = service.importData(data, deleteExisting = false, trackHistory = true)
        val replay = service.importData(data, deleteExisting = false, trackHistory = true)

        assertFalse(result.changed)
        assertEquals(1, result.matched)
        assertEquals(1, replay.alreadyProcessed)
        assertFalse(replay.changed)
        assertEquals(1L, database.notesQueries.countAll().executeAsOne())
        assertEquals(0, syncClient.localDataUpdatedCount)
    }

    @Test
    fun `tracked replay does not schedule another sync`() = runTest(dispatcher) {
        service.importData(data, deleteExisting = false, trackHistory = true)

        val result = service.importData(data, deleteExisting = false, trackHistory = true)

        assertFalse(result.changed)
        assertEquals(1, result.alreadyProcessed)
        assertEquals(1, syncClient.localDataUpdatedCount)
    }

    @Test
    fun `import during managed reset throws without writing data or history`() = runTest(dispatcher) {
        advanceUntilIdle()

        lifecycleCoordinator.runManagedReset {
            assertFailsWith<SessionResetInProgressException> {
                service.importData(data, deleteExisting = false, trackHistory = true)
            }
        }

        assertEquals(0L, database.notesQueries.countAll().executeAsOne())
        assertEquals(0, syncClient.localDataUpdatedCount)
        val retry = service.importData(data, deleteExisting = false, trackHistory = true)
        assertEquals(0, retry.alreadyProcessed)
        assertEquals(1, retry.notesImported)
    }
}

@BindingContainer
private class ImportSyncBindings(private val client: ImportSyncClientSpy) {
    @Provides
    fun synchronizationClientFactory(): QuranDataServiceSynchronizationClientFactory =
        QuranDataServiceSynchronizationClientFactory { _, _, _, _, _, _ -> client }
}

private class ImportSyncClientSpy : SynchronizationClient {
    var localDataUpdatedCount = 0
        private set

    override fun localDataUpdated() { localDataUpdatedCount++ }
    override fun applicationStarted() = Unit
    override fun triggerSyncImmediately() = Unit
    override fun cancelSyncing() = Unit
    override suspend fun cancelSyncingAndJoin() = Unit
}

private class LoggedOutAuthRepository : AuthRepository {
    override suspend fun login(): Unit = error("Login is not used by import tests")
    override suspend fun loginWithReauthentication(): Unit = error("Login is not used by import tests")
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
