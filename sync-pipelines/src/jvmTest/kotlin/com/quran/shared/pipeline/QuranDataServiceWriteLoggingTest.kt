package com.quran.shared.pipeline

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.LogoutTokenMaterial
import com.quran.shared.auth.repository.RemoteLogoutFailure
import com.quran.shared.auth.repository.RemoteLogoutMode
import com.quran.shared.auth.service.AuthService
import com.quran.shared.persistence.DuplicateCollectionNameException
import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.ReadOnlyCollectionException
import com.quran.shared.persistence.ReservedCollectionNameException
import com.quran.shared.persistence.TextLengthLimitException
import com.quran.shared.persistence.repository.PersistenceResetRepositoryImpl
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepositoryImpl
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.repository.note.repository.NotesRepositoryImpl
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepositoryImpl
import com.quran.shared.pipeline.sync.SynchronizationClient
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createDynamicGraphFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hosts forward warnings and errors to crash reporting, so a write the data rules reject logs at info, without the
 * stack trace or the user's text, while any other failed write logs at error with its throwable.
 */
class QuranDataServiceWriteLoggingTest {
    private data class Entry(val severity: Severity, val message: String, val throwable: Throwable?)

    private val entries = mutableListOf<Entry>()
    private val writer = object : LogWriter() {
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            synchronized(entries) { entries += Entry(severity, message, throwable) }
        }
    }
    private val dispatcher = StandardTestDispatcher()
    private lateinit var database: MobileSyncDatabase
    private lateinit var authService: AuthService
    private lateinit var lifecycleCoordinator: SessionLifecycleCoordinator
    private lateinit var service: QuranDataService

    @BeforeTest
    fun setUp() {
        Logger.addLogWriter(writer)
        Dispatchers.setMain(dispatcher)
        database = inMemoryMobileSyncDatabase()
        val synced = database.synced
        authService = AuthService(WriteLoggingAuthRepository())
        lifecycleCoordinator = SessionLifecycleCoordinator(
            SettingsSessionLifecycleStateStore(MapSettings().toSuspendSettings())
        )
        val pipeline = SyncEnginePipeline(
            bookmarksRepository = BookmarksRepositoryImpl(synced),
            readingBookmarksRepository = ReadingBookmarksRepositoryImpl(synced),
            collectionsRepository = CollectionsRepositoryImpl(synced),
            collectionBookmarksRepository = CollectionBookmarksRepositoryImpl(synced),
            notesRepository = NotesRepositoryImpl(synced),
            readingSessionsRepository = ReadingSessionsRepositoryImpl(synced)
        )
        service = createDynamicGraphFactory<QuranDataServiceTestGraph.Factory>(IdleSyncBindings())
            .create(
                authService = authService,
                pipeline = pipeline,
                environment = SynchronizationEnvironment("https://example.invalid"),
                persistenceResetRepository = PersistenceResetRepositoryImpl(synced),
                persistenceImportRepository = PersistenceImportRepositoryImpl(database.quran, synced),
                sessionLifecycleCoordinator = lifecycleCoordinator,
                database = synced
            ).quranDataService
    }

    @AfterTest
    fun tearDown() {
        runTest(dispatcher) {
            service.clearAndJoin()
            authService.clearAndJoin()
        }
        database.driver.close()
        Dispatchers.resetMain()
        Logger.setLogWriters(Logger.config.logWriterList - writer)
    }

    @Test
    fun `a note too short logs at info without the throwable or its text`() = runTest(dispatcher) {
        assertFailsWith<TextLengthLimitException> { service.addNote("Zq9x", 2, 255, 2, 255) }

        val entry = failure("Failed to add note")
        assertEquals(Severity.Info, entry.severity)
        assertNull(entry.throwable)
        assertTrue("NOTE_BODY TOO_SHORT" in entry.message, entry.message)
        assertNothingLogged("Zq9x")
    }

    @Test
    fun `a collection name too long logs at info without the name`() = runTest(dispatcher) {
        val name = "Zq9x".repeat(64)

        assertFailsWith<TextLengthLimitException> { service.addCollection(name) }

        val entry = failure("Failed to add collection")
        assertEquals(Severity.Info, entry.severity)
        assertNull(entry.throwable)
        assertTrue("COLLECTION_NAME TOO_LONG" in entry.message, entry.message)
        assertNothingLogged("Zq9x")
    }

    @Test
    fun `a reserved collection name logs at info without the name`() = runTest(dispatcher) {
        assertFailsWith<ReservedCollectionNameException> { service.addCollection("system:Zq9x") }

        assertRejected("Failed to add collection")
        assertNothingLogged("Zq9x")
    }

    @Test
    fun `renaming a collection to a taken name logs at info without either name`() = runTest(dispatcher) {
        service.addCollection("Zq9x tafsir")
        val other = service.addCollection("Zq9x memorize")

        assertFailsWith<DuplicateCollectionNameException> { service.updateCollection(other.id, "zq9x TAFSIR") }

        assertRejected("Failed to update collection")
        assertNothingLogged("Zq9x")
    }

    @Test
    fun `renaming Favorites logs at info without the name`() = runTest(dispatcher) {
        val favorites = CollectionsRepositoryImpl(database.synced).getAllCollections().single { it.isDefault }

        assertFailsWith<ReadOnlyCollectionException> { service.updateCollection(favorites.id, "Zq9x") }

        assertRejected("Failed to update collection")
        assertNothingLogged("Zq9x")
    }

    @Test
    fun `a write during a session reset logs at info`() = runTest(dispatcher) {
        service.awaitInitialization()

        lifecycleCoordinator.runManagedReset {
            assertFailsWith<SessionResetInProgressException> { service.addNote("Zq9x reflection", 2, 255, 2, 255) }
        }

        assertRejected("Failed to add note")
        assertNothingLogged("Zq9x")
    }

    @Test
    fun `an id that names no note logs at error with the throwable`() = runTest(dispatcher) {
        assertFailsWith<IllegalArgumentException> { service.updateNote("404", "A reflection on mercy") }

        val entry = failure("Failed to update note")
        assertEquals(Severity.Error, entry.severity)
        assertIs<IllegalArgumentException>(entry.throwable)
    }

    @Test
    fun `a write the database fails logs at error with the throwable`() = runTest(dispatcher) {
        service.awaitInitialization()
        database.driver.close()

        val thrown = assertFailsWith<Exception> { service.addNote("A reflection on mercy", 2, 255, 2, 255) }

        val entry = failure("Failed to add note")
        assertEquals(Severity.Error, entry.severity)
        assertNotNull(entry.throwable)
        assertEquals(thrown, entry.throwable)
        assertFalse(thrown is IllegalArgumentException || thrown is IllegalStateException, thrown.toString())
    }

    private fun assertRejected(message: String) {
        val entry = failure(message)
        assertEquals(Severity.Info, entry.severity)
        assertNull(entry.throwable)
    }

    /** The one entry [QuranDataService] logged for the failed write. */
    private fun failure(message: String): Entry = synchronized(entries) {
        entries.single { it.message.startsWith(message) }
    }

    private fun assertNothingLogged(text: String) = synchronized(entries) {
        val leaked = entries.filter { entry ->
            entry.message.contains(text, ignoreCase = true) ||
                entry.throwable?.stackTraceToString()?.contains(text, ignoreCase = true) == true
        }
        assertEquals(emptyList(), leaked)
    }
}

@BindingContainer
private class IdleSyncBindings {
    @Provides
    fun synchronizationClientFactory(): QuranDataServiceSynchronizationClientFactory =
        QuranDataServiceSynchronizationClientFactory { _, _, _, _ -> IdleSyncClient }
}

/** Sync is out of scope here: triggers do nothing, so no run logs while a write is checked. */
private object IdleSyncClient : SynchronizationClient {
    override fun localDataUpdated() = Unit
    override fun applicationStarted() = Unit
    override fun triggerSyncImmediately() = Unit
    override fun cancelSyncing() = Unit
    override suspend fun cancelSyncingAndJoin() = Unit
}

private class WriteLoggingAuthRepository : AuthRepository {
    override suspend fun login(): Unit = error("Login is not used by write logging tests")
    override suspend fun loginWithReauthentication(): Unit = error("Login is not used by write logging tests")
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
