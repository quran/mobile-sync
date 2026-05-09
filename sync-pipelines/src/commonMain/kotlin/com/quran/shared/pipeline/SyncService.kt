package com.quran.shared.pipeline

import co.touchlab.kermit.Logger
import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.service.AuthService
import com.quran.shared.di.AppScope
import com.quran.shared.persistence.model.AyahBookmark
import com.quran.shared.persistence.model.AyahReadingBookmark
import com.quran.shared.persistence.model.CollectionAyahBookmark
import com.quran.shared.persistence.model.CollectionWithAyahBookmarks
import com.quran.shared.persistence.model.Note
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmark
import com.quran.shared.persistence.model.ReadingSession
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.input.PersistenceImportResult
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepository
import com.quran.shared.persistence.repository.PersistenceResetRepository
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepository
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepository
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepository
import com.quran.shared.persistence.repository.note.repository.NotesRepository
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepository
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepository
import com.quran.shared.syncengine.AuthenticationDataFetcher
import com.quran.shared.syncengine.LocalModificationDateFetcher
import com.quran.shared.syncengine.SynchronizationClient
import com.quran.shared.syncengine.SynchronizationEnvironment
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Orchestrates synchronization and provides a unified data layer for the UI.
 *
 * This class must be obtained from the [com.quran.shared.pipeline.di.AppGraph] DI graph
 * via [com.quran.shared.pipeline.di.SharedDependencyGraph]. Direct construction is not supported.
 */
@Inject
@SingleIn(AppScope::class)
class SyncService(
    private val authService: AuthService,
    private val pipeline: SyncEnginePipeline,
    private val environment: SynchronizationEnvironment,
    private val persistenceResetRepository: PersistenceResetRepository,
    private val persistenceImportRepository: PersistenceImportRepository,
    private val settings: Settings
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val syncClient: SynchronizationClient

    fun clear() {
        scope.cancel()
        syncClient.cancelSyncing()
    }

    @NativeCoroutinesState
    val authState: StateFlow<AuthState> get() = authService.authState

    // Cast the synchronization repository to the transactional repository
    // This allows the Service to be the single entry point for UI
    private val bookmarksRepository = pipeline.bookmarksRepository as BookmarksRepository
    private val readingBookmarksRepository = pipeline.readingBookmarksRepository as ReadingBookmarksRepository
    private val collectionsRepository = pipeline.collectionsRepository as CollectionsRepository
    private val collectionBookmarksRepository =
        pipeline.collectionBookmarksRepository as CollectionBookmarksRepository
    private val notesRepository = pipeline.notesRepository as NotesRepository
    private val readingSessionsRepository = pipeline.readingSessionsRepository as ReadingSessionsRepository

    /**
     * Flow of all bookmarks for the UI to observe.
     */
    @NativeCoroutines
    val bookmarks: Flow<List<AyahBookmark>> get() = bookmarksRepository.getBookmarksFlow()

    @NativeCoroutines
    val readingBookmark: Flow<ReadingBookmark?> get() = readingBookmarksRepository.getReadingBookmarkFlow()

    /**
     * Flow of all collections with their bookmarks for the UI to observe.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @NativeCoroutines
    val collectionsWithBookmarks: Flow<List<CollectionWithAyahBookmarks>>
        get() =
            collectionsRepository.getCollectionsFlow().flatMapLatest { collections ->
                if (collections.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    val flows = collections.map { collection ->
                        collectionBookmarksRepository.getBookmarksForCollectionFlow(collection.localId)
                            .map { bookmarks: List<CollectionAyahBookmark> ->
                                CollectionWithAyahBookmarks(collection, bookmarks)
                            }
                    }
                    combine(flows) { it.toList() }
                }
            }

    /**
     * Flow of all notes for the UI to observe.
     */
    @NativeCoroutines
    val notes: Flow<List<Note>> get() = notesRepository.getNotesFlow()

    /**
     * Flow of all reading sessions for the UI to observe.
     */
    @NativeCoroutines
    val readingSessions: Flow<List<ReadingSession>> get() = readingSessionsRepository.getReadingSessionsFlow()

    init {
        val dateFetcher = SettingsLocalModificationDateFetcher(settings)
        val authFetcher = object : AuthenticationDataFetcher {
            override suspend fun fetchAuthenticationHeaders(): Map<String, String> {
                return authService.getAuthHeaders()
            }

            override fun isLoggedIn(): Boolean {
                return authService.isLoggedIn()
            }
        }

        syncClient = pipeline.setup(
            environment = environment,
            localModificationDateFetcher = dateFetcher,
            authenticationDataFetcher = authFetcher,
            callback = object : SyncEngineCallback {
                override fun synchronizationDone(newLastModificationDate: Long) {
                    Logger.i { "Sync completed. New last modified: $newLastModificationDate" }
                    dateFetcher.updateLastModificationDate(newLastModificationDate)
                }

                override fun encounteredError(errorMsg: String) {
                    Logger.e { "Sync error: $errorMsg" }
                }
            }
        )

        scope.launch {
            syncClient.applicationStarted()

            // Observe auth state and trigger sync when logged in
            authState.collect { state ->
                if (state is AuthState.Success) {
                    syncClient.triggerSyncImmediately()
                } else if (state is AuthState.Idle || state is AuthState.Error) {
                    syncClient.cancelSyncing()
                }
            }
        }
    }

    @NativeCoroutines
    suspend fun logout(clearLocalData: Boolean = false) {
        try {
            authService.logout()
            syncClient.cancelSyncing()
            if (clearLocalData) {
                persistenceResetRepository.deleteAllData()
                SettingsLocalModificationDateFetcher(settings).updateLastModificationDate(0L)
            }
        } catch (e: Exception) {
            Logger.e(e) { "Logout failed" }
            throw e
        }
    }

    fun triggerSync() {
        syncClient.localDataUpdated()
    }

    @NativeCoroutines
    suspend fun importData(data: PersistenceImportData): PersistenceImportResult {
        try {
            val result = persistenceImportRepository.importData(data)
            triggerSync()
            return result
        } catch (e: Exception) {
            Logger.e(e) { "Failed to import persistence data" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun addBookmark(sura: Int, ayah: Int): AyahBookmark {
        try {
            val bookmark = bookmarksRepository.addBookmark(sura, ayah)
            triggerSync()
            return bookmark
        } catch (e: Exception) {
            Logger.e(e) { "Failed to add ayah bookmark" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun addBookmark(sura: Int, ayah: Int, timestamp: PlatformDateTime): AyahBookmark {
        try {
            val bookmark = bookmarksRepository.addBookmark(sura, ayah, timestamp)
            triggerSync()
            return bookmark
        } catch (e: Exception) {
            Logger.e(e) { "Failed to add ayah bookmark" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun addReadingBookmark(sura: Int, ayah: Int): AyahReadingBookmark {
        return addAyahReadingBookmark(sura, ayah)
    }

    @NativeCoroutines
    suspend fun addReadingBookmark(sura: Int, ayah: Int, timestamp: PlatformDateTime): AyahReadingBookmark {
        return addAyahReadingBookmark(sura, ayah, timestamp)
    }

    @NativeCoroutines
    suspend fun addAyahReadingBookmark(sura: Int, ayah: Int): AyahReadingBookmark {
        try {
            val bookmark = readingBookmarksRepository.addAyahReadingBookmark(sura, ayah)
            triggerSync()
            return bookmark
        } catch (e: Exception) {
            Logger.e(e) { "Failed to add reading ayah bookmark" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun addAyahReadingBookmark(sura: Int, ayah: Int, timestamp: PlatformDateTime): AyahReadingBookmark {
        try {
            val bookmark = readingBookmarksRepository.addAyahReadingBookmark(sura, ayah, timestamp)
            triggerSync()
            return bookmark
        } catch (e: Exception) {
            Logger.e(e) { "Failed to add reading ayah bookmark" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun addPageReadingBookmark(page: Int): PageReadingBookmark {
        try {
            val bookmark = readingBookmarksRepository.addPageReadingBookmark(page)
            triggerSync()
            return bookmark
        } catch (e: Exception) {
            Logger.e(e) { "Failed to add reading page bookmark" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun addPageReadingBookmark(page: Int, timestamp: PlatformDateTime): PageReadingBookmark {
        try {
            val bookmark = readingBookmarksRepository.addPageReadingBookmark(page, timestamp)
            triggerSync()
            return bookmark
        } catch (e: Exception) {
            Logger.e(e) { "Failed to add reading page bookmark" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun addReadingSession(sura: Int, ayah: Int): ReadingSession {
        try {
            val readingSession = readingSessionsRepository.addReadingSession(sura, ayah)
            triggerSync()
            return readingSession
        } catch (e: Exception) {
            Logger.e(e) { "Failed to add reading session" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun addReadingSession(sura: Int, ayah: Int, timestamp: PlatformDateTime): ReadingSession {
        try {
            val readingSession = readingSessionsRepository.addReadingSession(sura, ayah, timestamp)
            triggerSync()
            return readingSession
        } catch (e: Exception) {
            Logger.e(e) { "Failed to add reading session" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun deleteReadingBookmark(): Boolean {
        try {
            val deleted = readingBookmarksRepository.deleteReadingBookmark()
            if (deleted) {
                triggerSync()
            }
            return deleted
        } catch (e: Exception) {
            Logger.e(e) { "Failed to delete current reading bookmark" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun deleteBookmark(bookmark: AyahBookmark) {
        try {
            bookmarksRepository.deleteBookmark(bookmark.sura, bookmark.ayah)
            triggerSync()
        } catch (e: Exception) {
            Logger.e(e) { "Failed to delete bookmark" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun addCollection(name: String) {
        try {
            collectionsRepository.addCollection(name)
            triggerSync()
        } catch (e: Exception) {
            Logger.e(e) { "Failed to add collection" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun addCollection(name: String, timestamp: PlatformDateTime) {
        try {
            collectionsRepository.addCollection(name, timestamp)
            triggerSync()
        } catch (e: Exception) {
            Logger.e(e) { "Failed to add collection" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun deleteCollection(localId: String) {
        try {
            collectionsRepository.deleteCollection(localId)
            triggerSync()
        } catch (e: Exception) {
            Logger.e(e) { "Failed to delete collection" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun addBookmarkToCollection(collectionLocalId: String, bookmark: AyahBookmark) {
        try {
            collectionBookmarksRepository.addBookmarkToCollection(collectionLocalId, bookmark)
            triggerSync()
        } catch (e: Exception) {
            Logger.e(e) { "Failed to add bookmark to collection" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun addBookmarkToCollection(
        collectionLocalId: String,
        bookmark: AyahBookmark,
        timestamp: PlatformDateTime
    ) {
        try {
            collectionBookmarksRepository.addBookmarkToCollection(collectionLocalId, bookmark, timestamp)
            triggerSync()
        } catch (e: Exception) {
            Logger.e(e) { "Failed to add bookmark to collection" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun addAyahBookmarkToCollection(
        collectionLocalId: String,
        sura: Int,
        ayah: Int
    ): CollectionAyahBookmark {
        try {
            val collectionBookmark = collectionBookmarksRepository
                .addAyahBookmarkToCollection(collectionLocalId, sura, ayah)
            triggerSync()
            return collectionBookmark
        } catch (e: Exception) {
            Logger.e(e) { "Failed to add ayah bookmark to collection" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun addAyahBookmarkToCollection(
        collectionLocalId: String,
        sura: Int,
        ayah: Int,
        timestamp: PlatformDateTime
    ): CollectionAyahBookmark {
        try {
            val collectionBookmark = collectionBookmarksRepository
                .addAyahBookmarkToCollection(collectionLocalId, sura, ayah, timestamp)
            triggerSync()
            return collectionBookmark
        } catch (e: Exception) {
            Logger.e(e) { "Failed to add ayah bookmark to collection" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun removeBookmarkFromCollection(collectionLocalId: String, bookmark: AyahBookmark) {
        try {
            collectionBookmarksRepository.removeBookmarkFromCollection(collectionLocalId, bookmark)
            triggerSync()
        } catch (e: Exception) {
            Logger.e(e) { "Failed to remove bookmark from collection" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun removeAyahBookmarkFromCollection(bookmark: CollectionAyahBookmark) {
        try {
            collectionBookmarksRepository.removeAyahBookmarkFromCollection(bookmark)
            triggerSync()
        } catch (e: Exception) {
            Logger.e(e) { "Failed to remove bookmark from collection" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun addNote(body: String, startSura: Int, startAyah: Int, endSura: Int, endAyah: Int) {
        try {
            notesRepository.addNote(body, startSura, startAyah, endSura, endAyah)
            triggerSync()
        } catch (e: Exception) {
            Logger.e(e) { "Failed to add note" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun addNote(
        body: String,
        startSura: Int,
        startAyah: Int,
        endSura: Int,
        endAyah: Int,
        timestamp: PlatformDateTime
    ) {
        try {
            notesRepository.addNote(body, startSura, startAyah, endSura, endAyah, timestamp)
            triggerSync()
        } catch (e: Exception) {
            Logger.e(e) { "Failed to add note" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun deleteNote(localId: String) {
        try {
            notesRepository.deleteNote(localId)
            triggerSync()
        } catch (e: Exception) {
            Logger.e(e) { "Failed to delete note" }
            throw e
        }
    }

    @NativeCoroutines
    fun getBookmarksForCollectionFlow(collectionLocalId: String): Flow<List<CollectionAyahBookmark>> =
        collectionBookmarksRepository.getBookmarksForCollectionFlow(collectionLocalId)

    val pipelineForIos: SyncEnginePipeline get() = pipeline
}

fun makeSettings(): Settings = Settings()

class SettingsLocalModificationDateFetcher(private val settings: Settings) :
    LocalModificationDateFetcher {
    private val KEY_LAST_MODIFIED = "com.quran.sync.last_modified_date"

    override suspend fun localLastModificationDate(): Long {
        return settings.getLong(KEY_LAST_MODIFIED, 0L)
    }

    fun updateLastModificationDate(date: Long) {
        settings[KEY_LAST_MODIFIED] = date
    }
}
