package com.quran.shared.pipeline

import co.touchlab.kermit.Logger
import com.quran.shared.auth.model.AuthState
import com.quran.shared.syncengine.AuthenticationDataFetcher
import com.quran.shared.syncengine.LocalModificationDateFetcher
import com.quran.shared.syncengine.SynchronizationClient
import com.quran.shared.syncengine.SynchronizationEnvironment
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import com.quran.shared.auth.service.AuthService
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.model.CollectionBookmark
import com.quran.shared.persistence.model.CollectionWithBookmarks
import com.quran.shared.persistence.model.Note
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepository
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepository
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepository
import com.quran.shared.persistence.repository.note.repository.NotesRepository
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SyncService(
    private val authService: AuthService,
    private val pipeline: SyncEnginePipeline,
    private val environment: SynchronizationEnvironment,
    private val settings: Settings = Settings()
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
    private val collectionsRepository = pipeline.collectionsRepository as CollectionsRepository
    private val collectionBookmarksRepository =
        pipeline.collectionBookmarksRepository as CollectionBookmarksRepository
    private val notesRepository = pipeline.notesRepository as NotesRepository

    /**
     * Flow of all bookmarks for the UI to observe.
     */
    @NativeCoroutines
    val bookmarks: Flow<List<Bookmark>> get() = bookmarksRepository.getBookmarksFlow()

    /**
     * Flow of all collections with their bookmarks for the UI to observe.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @NativeCoroutines
    val collectionsWithBookmarks: Flow<List<CollectionWithBookmarks>>
        get() =
            collectionsRepository.getCollectionsFlow().flatMapLatest { collections ->
                if (collections.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    val flows = collections.map { collection ->
                        collectionBookmarksRepository.getBookmarksForCollectionFlow(collection.localId)
                            .map { bookmarks: List<CollectionBookmark> ->
                                CollectionWithBookmarks(collection, bookmarks)
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

    fun triggerSync() {
        syncClient.localDataUpdated()
    }

    @NativeCoroutines
    suspend fun addBookmark(page: Int): Bookmark {
        try {
            val bookmark = bookmarksRepository.addBookmark(page)
            triggerSync()
            return bookmark
        } catch (e: Exception) {
            Logger.e(e) { "Failed to add page bookmark" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun addBookmark(sura: Int, ayah: Int): Bookmark {
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
    suspend fun deleteBookmark(bookmark: Bookmark): Unit {
        try {
            when (bookmark) {
                is Bookmark.PageBookmark -> bookmarksRepository.deleteBookmark(bookmark.page)
                is Bookmark.AyahBookmark -> bookmarksRepository.deleteBookmark(
                    bookmark.sura,
                    bookmark.ayah
                )
            }
            triggerSync()
        } catch (e: Exception) {
            Logger.e(e) { "Failed to delete bookmark" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun addCollection(name: String): Unit {
        try {
            collectionsRepository.addCollection(name)
            triggerSync()
        } catch (e: Exception) {
            Logger.e(e) { "Failed to add collection" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun deleteCollection(localId: String): Unit {
        try {
            collectionsRepository.deleteCollection(localId)
            triggerSync()
        } catch (e: Exception) {
            Logger.e(e) { "Failed to delete collection" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun addBookmarkToCollection(collectionLocalId: String, bookmark: Bookmark): Unit {
        try {
            collectionBookmarksRepository.addBookmarkToCollection(collectionLocalId, bookmark)
            triggerSync()
        } catch (e: Exception) {
            Logger.e(e) { "Failed to add bookmark to collection" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun removeBookmarkFromCollection(collectionLocalId: String, bookmark: Bookmark): Unit {
        try {
            collectionBookmarksRepository.removeBookmarkFromCollection(collectionLocalId, bookmark)
            triggerSync()
        } catch (e: Exception) {
            Logger.e(e) { "Failed to remove bookmark from collection" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun addNote(body: String, startAyahId: Long, endAyahId: Long): Unit {
        try {
            notesRepository.addNote(body, startAyahId, endAyahId)
            triggerSync()
        } catch (e: Exception) {
            Logger.e(e) { "Failed to add note" }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun deleteNote(localId: String): Unit {
        try {
            notesRepository.deleteNote(localId)
            triggerSync()
        } catch (e: Exception) {
            Logger.e(e) { "Failed to delete note" }
            throw e
        }
    }

    @NativeCoroutines
    fun getBookmarksForCollectionFlow(collectionLocalId: String): Flow<List<CollectionBookmark>> =
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
