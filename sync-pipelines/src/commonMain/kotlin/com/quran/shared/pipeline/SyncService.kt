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
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepository
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SyncService(
    private val authService: AuthService,
    private val pipeline: SyncEnginePipeline,
    private val environment: SynchronizationEnvironment,
    private val settings: Settings = Settings()
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val syncClient: SynchronizationClient
    
    @NativeCoroutinesState
    val authState: StateFlow<AuthState> get() = authService.authState
    
    // Cast the synchronization repository to the transactional repository
    // This allows the Service to be the single entry point for UI
    private val bookmarksRepository = pipeline.bookmarksRepository as BookmarksRepository

    /**
     * Flow of all bookmarks for the UI to observe.
     */
    @NativeCoroutines
    val bookmarks: Flow<List<Bookmark>> get() = bookmarksRepository.getBookmarksFlow()

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
    suspend fun addBookmark(page: Int): Unit {
        try {
            bookmarksRepository.addBookmark(page)
            triggerSync()
        } catch (e: Exception) {
            Logger.e(e) { "Failed to add bookmark" }
            throw e
        }
    }

    val pipelineForIos: SyncEnginePipeline get() = pipeline
}

fun makeSettings(): Settings = Settings()

class SettingsLocalModificationDateFetcher(private val settings: Settings) : LocalModificationDateFetcher {
    private val KEY_LAST_MODIFIED = "com.quran.sync.last_modified_date"

    override suspend fun localLastModificationDate(): Long {
        return settings.getLong(KEY_LAST_MODIFIED, 0L)
    }

    fun updateLastModificationDate(date: Long) {
        settings[KEY_LAST_MODIFIED] = date
    }
}
