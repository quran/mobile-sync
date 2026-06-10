package com.quran.shared.pipeline

import co.touchlab.kermit.Logger
import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.repository.LogoutTokenCaptureException
import com.quran.shared.auth.repository.LogoutTokenMaterial
import com.quran.shared.auth.repository.RemoteLogoutOperation
import com.quran.shared.auth.service.AuthService
import com.quran.shared.di.AppScope
import com.quran.shared.persistence.model.AyahBookmark
import com.quran.shared.persistence.model.AyahReadingBookmark
import com.quran.shared.persistence.model.CollectionAyahBookmark
import com.quran.shared.persistence.model.CollectionWithAyahBookmarks
import com.quran.shared.persistence.model.DEFAULT_COLLECTION_ID
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
import com.quran.shared.syncengine.SynchronizationClient
import com.quran.shared.syncengine.SynchronizationEnvironment
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
class SyncService internal constructor(
    private val authService: AuthService,
    private val pipeline: SyncEnginePipeline,
    private val environment: SynchronizationEnvironment,
    private val persistenceResetRepository: PersistenceResetRepository,
    private val persistenceImportRepository: PersistenceImportRepository,
    private val syncLocalModificationDateStore: SyncLocalModificationDateStore,
    private val sessionLifecycleCoordinator: SessionLifecycleCoordinator
) {
    val defaultCollectionId: String = DEFAULT_COLLECTION_ID


    private val serviceJob: Job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val syncClient: SynchronizationClient

    fun clear() {
        serviceJob.cancel()
        syncClient.cancelSyncing()
    }

    suspend fun clearAndJoin() {
        clear()
        serviceJob.cancelAndJoin()
        syncClient.cancelSyncingAndJoin()
    }

    @NativeCoroutinesState
    val authState: StateFlow<AuthState> get() = authService.authState

    // Cast the synchronization repository to the transactional repository
    // This allows the Service to be the single entry point for UI
    private val bookmarksRepository = pipeline.bookmarksRepository as BookmarksRepository
    private val readingBookmarksRepository = pipeline.readingBookmarksRepository
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
            localModificationDateFetcher = syncLocalModificationDateStore,
            authenticationDataFetcher = authFetcher,
            syncLifecycleGate = sessionLifecycleCoordinator,
            callback = SettingsSyncEngineCallback(syncLocalModificationDateStore, CurrentSyncWriteBoundaryGuard)
        )

        scope.launch {
            try {
                sessionLifecycleCoordinator.completePersistedResetIfNeeded {
                    syncClient.cancelSyncingAndJoin()
                    resetLocalAuthDataAndToken()
                }
            } catch (e: Exception) {
                Logger.e(e) { "Failed to recover persisted session reset" }
                return@launch
            }

            syncClient.applicationStarted()

            // Observe auth state and trigger sync when a session is published. Logged-out sync
            // attempts no-op after fetching empty headers; managed reset owns cancellation.
            authState.collect { state ->
                if (state is AuthState.Success && sessionLifecycleCoordinator.canStartSync()) {
                    syncClient.triggerSyncImmediately()
                }
            }
        }
    }

    @NativeCoroutines
    suspend fun logout(clearLocalData: Boolean = true): LogoutResult {
        if (!clearLocalData) {
            throw UnsupportedOperationException("Keep-local logout is not implemented yet")
        }
        var tokenMaterial: LogoutTokenMaterial? = null
        var tokenCaptureFailure: Throwable? = null
        try {
            sessionLifecycleCoordinator.runManagedReset(
                beforeWriteDrain = {
                    try {
                        authService.captureLogoutTokenMaterialForLogout()
                    } catch (e: LogoutTokenCaptureException) {
                        tokenCaptureFailure = e.cause ?: e
                        null
                    }.let { captured ->
                        tokenMaterial = captured
                    }
                }
            ) {
                syncClient.cancelSyncingAndJoin()
                resetLocalDataAndSyncToken()
            }
            val warnings = tokenCaptureFailure?.let { failure ->
                logoutRemoteCleanupFailureWarnings(failure)
            } ?: try {
                authService.attemptRemoteLogout(tokenMaterial!!).let { failures ->
                    failures.map { failure ->
                        LogoutWarning(
                            type = when (failure.operation) {
                                RemoteLogoutOperation.REVOKE_REFRESH_TOKEN -> LogoutWarningType.REVOKE_TOKEN_FAILED
                                RemoteLogoutOperation.END_SESSION -> LogoutWarningType.END_SESSION_FAILED
                            },
                            message = failure.exception.message
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (failure: Throwable) {
                logoutRemoteCleanupFailureWarnings(failure)
            }
            return LogoutResult(warnings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e) { "Logout failed" }
            throw e
        }
    }

    fun triggerSync() {
        if (!sessionLifecycleCoordinator.canStartSync()) {
            Logger.d { "Sync trigger skipped because session is resetting" }
            return
        }
        syncClient.localDataUpdated()
    }

    private suspend fun resetLocalAuthDataAndToken() {
        authService.clearLocalSession()
        resetLocalDataAndSyncToken()
    }

    private suspend fun resetLocalDataAndSyncToken() {
        persistenceResetRepository.deleteAllData()
        syncLocalModificationDateStore.updateLastModificationDate(0L)
    }

    private fun logoutRemoteCleanupFailureWarnings(failure: Throwable): List<LogoutWarning> =
        listOf(
            LogoutWarning(
                type = LogoutWarningType.REVOKE_TOKEN_FAILED,
                message = failure.message
            ),
            LogoutWarning(
                type = LogoutWarningType.END_SESSION_FAILED,
                message = failure.message
            )
        )

    private suspend fun <T> mutatingCall(
        errorMessage: String,
        triggerAfter: Boolean = true,
        block: suspend () -> T
    ): T {
        try {
            return sessionLifecycleCoordinator.withMutatingWrite {
                val result = block()
                if (triggerAfter) {
                    triggerSync()
                }
                result
            }
        } catch (e: Exception) {
            Logger.e(e) { errorMessage }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun importData(data: PersistenceImportData): PersistenceImportResult {
        return importData(
            data = data,
            deleteExisting = false
        )
    }

    @NativeCoroutines
    suspend fun importData(
        data: PersistenceImportData,
        deleteExisting: Boolean
    ): PersistenceImportResult {
        return mutatingCall("Failed to import persistence data") {
            persistenceImportRepository.importData(
                data = data,
                deleteExisting = deleteExisting
            )
        }
    }

    @NativeCoroutines
    suspend fun addBookmark(sura: Int, ayah: Int): AyahBookmark {
        return mutatingCall("Failed to add ayah bookmark") {
            bookmarksRepository.addBookmark(sura, ayah)
        }
    }

    @NativeCoroutines
    suspend fun addBookmark(sura: Int, ayah: Int, timestamp: PlatformDateTime): AyahBookmark {
        return mutatingCall("Failed to add ayah bookmark") {
            bookmarksRepository.addBookmark(sura, ayah, timestamp)
        }
    }

    @NativeCoroutines
    suspend fun addBookmark(sura: Int, ayah: Int, collectionLocalIds: List<String>?): AyahBookmark {
        return mutatingCall("Failed to add ayah bookmark with collection memberships") {
            bookmarksRepository.addBookmark(sura, ayah, collectionLocalIds)
        }
    }

    @NativeCoroutines
    suspend fun addBookmark(
        sura: Int,
        ayah: Int,
        collectionLocalIds: List<String>?,
        timestamp: PlatformDateTime
    ): AyahBookmark {
        return mutatingCall("Failed to add ayah bookmark with collection memberships") {
            bookmarksRepository.addBookmark(sura, ayah, collectionLocalIds, timestamp)
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
        return mutatingCall("Failed to add reading ayah bookmark") {
            readingBookmarksRepository.addAyahReadingBookmark(sura, ayah)
        }
    }

    @NativeCoroutines
    suspend fun addAyahReadingBookmark(sura: Int, ayah: Int, timestamp: PlatformDateTime): AyahReadingBookmark {
        return mutatingCall("Failed to add reading ayah bookmark") {
            readingBookmarksRepository.addAyahReadingBookmark(sura, ayah, timestamp)
        }
    }

    @NativeCoroutines
    suspend fun addPageReadingBookmark(page: Int): PageReadingBookmark {
        return mutatingCall("Failed to add reading page bookmark") {
            readingBookmarksRepository.addPageReadingBookmark(page)
        }
    }

    @NativeCoroutines
    suspend fun addPageReadingBookmark(page: Int, timestamp: PlatformDateTime): PageReadingBookmark {
        return mutatingCall("Failed to add reading page bookmark") {
            readingBookmarksRepository.addPageReadingBookmark(page, timestamp)
        }
    }

    @NativeCoroutines
    suspend fun addReadingSession(sura: Int, ayah: Int): ReadingSession {
        return mutatingCall("Failed to add reading session") {
            readingSessionsRepository.addReadingSession(sura, ayah)
        }
    }

    @NativeCoroutines
    suspend fun addReadingSession(sura: Int, ayah: Int, timestamp: PlatformDateTime): ReadingSession {
        return mutatingCall("Failed to add reading session") {
            readingSessionsRepository.addReadingSession(sura, ayah, timestamp)
        }
    }

    @NativeCoroutines
    suspend fun updateReadingSession(localId: String, sura: Int, ayah: Int): ReadingSession {
        return mutatingCall("Failed to update reading session") {
            readingSessionsRepository.updateReadingSession(localId, sura, ayah)
        }
    }

    @NativeCoroutines
    suspend fun updateReadingSession(
        localId: String,
        sura: Int,
        ayah: Int,
        timestamp: PlatformDateTime
    ): ReadingSession {
        return mutatingCall("Failed to update reading session") {
            readingSessionsRepository.updateReadingSession(localId, sura, ayah, timestamp)
        }
    }

    @NativeCoroutines
    suspend fun deleteReadingBookmark(): Boolean {
        return mutatingCall("Failed to delete current reading bookmark", triggerAfter = false) {
            val deleted = readingBookmarksRepository.deleteReadingBookmark()
            if (deleted) {
                triggerSync()
            }
            deleted
        }
    }

    @NativeCoroutines
    suspend fun deleteBookmark(bookmark: AyahBookmark) {
        mutatingCall("Failed to delete bookmark") {
            bookmarksRepository.deleteBookmark(bookmark)
        }
    }

    @NativeCoroutines
    suspend fun addCollection(name: String) {
        mutatingCall("Failed to add collection") {
            collectionsRepository.addCollection(name)
        }
    }

    @NativeCoroutines
    suspend fun addCollection(name: String, timestamp: PlatformDateTime) {
        mutatingCall("Failed to add collection") {
            collectionsRepository.addCollection(name, timestamp)
        }
    }

    @NativeCoroutines
    suspend fun deleteCollection(localId: String) {
        mutatingCall("Failed to delete collection") {
            collectionsRepository.deleteCollection(localId)
        }
    }

    @NativeCoroutines
    suspend fun addBookmarkToCollection(collectionLocalId: String, bookmark: AyahBookmark) {
        mutatingCall("Failed to add bookmark to collection") {
            collectionBookmarksRepository.addBookmarkToCollection(collectionLocalId, bookmark)
        }
    }

    @NativeCoroutines
    suspend fun addBookmarkToCollection(
        collectionLocalId: String,
        bookmark: AyahBookmark,
        timestamp: PlatformDateTime
    ) {
        mutatingCall("Failed to add bookmark to collection") {
            collectionBookmarksRepository.addBookmarkToCollection(collectionLocalId, bookmark, timestamp)
        }
    }

    @NativeCoroutines
    suspend fun addAyahBookmarkToCollection(
        collectionLocalId: String,
        sura: Int,
        ayah: Int
    ): CollectionAyahBookmark {
        return mutatingCall("Failed to add ayah bookmark to collection") {
            collectionBookmarksRepository.addAyahBookmarkToCollection(collectionLocalId, sura, ayah)
        }
    }

    @NativeCoroutines
    suspend fun addAyahBookmarkToCollection(
        collectionLocalId: String,
        sura: Int,
        ayah: Int,
        timestamp: PlatformDateTime
    ): CollectionAyahBookmark {
        return mutatingCall("Failed to add ayah bookmark to collection") {
            collectionBookmarksRepository.addAyahBookmarkToCollection(collectionLocalId, sura, ayah, timestamp)
        }
    }

    @NativeCoroutines
    suspend fun removeBookmarkFromCollection(collectionLocalId: String, bookmark: AyahBookmark) {
        mutatingCall("Failed to remove bookmark from collection") {
            collectionBookmarksRepository.removeBookmarkFromCollection(collectionLocalId, bookmark)
        }
    }

    @NativeCoroutines
    suspend fun removeAyahBookmarkFromCollection(bookmark: CollectionAyahBookmark) {
        mutatingCall("Failed to remove bookmark from collection") {
            collectionBookmarksRepository.removeAyahBookmarkFromCollection(bookmark)
        }
    }

    @NativeCoroutines
    suspend fun addNote(body: String, startSura: Int, startAyah: Int, endSura: Int, endAyah: Int) {
        mutatingCall("Failed to add note") {
            notesRepository.addNote(body, startSura, startAyah, endSura, endAyah)
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
        mutatingCall("Failed to add note") {
            notesRepository.addNote(body, startSura, startAyah, endSura, endAyah, timestamp)
        }
    }

    @NativeCoroutines
    suspend fun deleteNote(localId: String) {
        mutatingCall("Failed to delete note") {
            notesRepository.deleteNote(localId)
        }
    }

    @NativeCoroutines
    fun getBookmarksForCollectionFlow(collectionLocalId: String): Flow<List<CollectionAyahBookmark>> =
        collectionBookmarksRepository.getBookmarksForCollectionFlow(collectionLocalId)
}

internal class SettingsSyncEngineCallback(
    private val syncLocalModificationDateStore: SyncLocalModificationDateStore,
    private val writeBoundaryGuard: SyncWriteBoundaryGuard = SyncWriteBoundaryGuard {}
) : SyncEngineCallback {
    override suspend fun synchronizationDone(newLastModificationDate: Long) {
        Logger.i { "Sync completed. New last modified: $newLastModificationDate" }
        syncLocalModificationDateStore.updateLastModificationDate(newLastModificationDate, writeBoundaryGuard)
    }

    override suspend fun encounteredError(errorMsg: String) {
        Logger.e { "Sync error: $errorMsg" }
    }
}
