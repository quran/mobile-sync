package com.quran.shared.pipeline

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.quran.shared.auth.service.AuthService
import com.quran.shared.persistence.DuplicateCollectionNameException
import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.TextLengthLimitException
import com.quran.shared.persistence.repository.PersistenceResetRepositoryImpl
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepositoryImpl
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.repository.note.repository.NotesRepositoryImpl
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepositoryImpl
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
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
import kotlin.test.assertNull

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
    private lateinit var service: QuranDataService

    @BeforeTest
    fun setUp() {
        Logger.addLogWriter(writer)
        Dispatchers.setMain(dispatcher)
        database = inMemoryMobileSyncDatabase()
        val synced = database.synced
        authService = AuthService(LoggedOutAuthRepository())
        val pipeline = SyncEnginePipeline(
            bookmarksRepository = BookmarksRepositoryImpl(synced),
            readingBookmarksRepository = ReadingBookmarksRepositoryImpl(synced),
            collectionsRepository = CollectionsRepositoryImpl(synced),
            collectionBookmarksRepository = CollectionBookmarksRepositoryImpl(synced),
            notesRepository = NotesRepositoryImpl(synced),
            readingSessionsRepository = ReadingSessionsRepositoryImpl(synced)
        )
        val bindings = ImportSyncBindings(ImportSyncClientSpy())
        service = createDynamicGraphFactory<QuranDataServiceTestGraph.Factory>(bindings)
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
        Logger.setLogWriters(Logger.config.logWriterList - writer)
        runTest(dispatcher) {
            service.clearAndJoin()
            authService.clearAndJoin()
        }
        database.driver.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a note too short logs at info without the throwable or its text`() = runTest(dispatcher) {
        assertFailsWith<TextLengthLimitException> { service.addNote("Zq9x", 2, 255, 2, 255) }

        val entry = failure("Failed to add note")
        assertEquals(Entry(Severity.Info, "Failed to add note: TextLengthLimitException", null), entry)
        assertFalse(logged().any { "Zq9x" in it.message })
    }

    @Test
    fun `renaming a collection to a taken name logs at info`() = runTest(dispatcher) {
        service.addCollection("Tafsir")
        val other = service.addCollection("Memorize")

        assertFailsWith<DuplicateCollectionNameException> { service.updateCollection(other.id, "tafsir") }

        val entry = failure("Failed to update collection")
        assertEquals(Severity.Info, entry.severity)
        assertNull(entry.throwable)
    }

    @Test
    fun `a write the database fails logs at error with the throwable`() = runTest(dispatcher) {
        service.awaitInitialization()
        database.driver.close()

        val thrown = assertFailsWith<Exception> { service.addNote("A reflection on mercy", 2, 255, 2, 255) }

        assertEquals(Entry(Severity.Error, "Failed to add note", thrown), failure("Failed to add note"))
    }

    private fun logged(): List<Entry> = synchronized(entries) { entries.toList() }

    private fun failure(message: String): Entry = logged().single { it.message.startsWith(message) }
}
