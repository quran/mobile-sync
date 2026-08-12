package com.quran.shared.pipeline

import co.touchlab.kermit.Logger
import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.repository.LogoutTokenCaptureException
import com.quran.shared.auth.repository.LogoutTokenMaterial
import com.quran.shared.auth.repository.RemoteLogoutFailure
import com.quran.shared.auth.repository.RemoteLogoutMode
import com.quran.shared.auth.repository.RemoteLogoutOperation
import com.quran.shared.auth.service.AuthService
import com.quran.shared.di.AppScope
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.input.PersistenceImportResult
import com.quran.shared.persistence.model.AyahBookmark
import com.quran.shared.persistence.model.AyahHighlight
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.AyahReadingBookmark
import com.quran.shared.persistence.model.Collection
import com.quran.shared.persistence.model.CollectionAyahBookmark
import com.quran.shared.persistence.model.CollectionWithAyahBookmarks
import com.quran.shared.persistence.model.Note
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmark
import com.quran.shared.persistence.model.ReadingSession
import com.quran.shared.persistence.repository.PersistenceResetRepository
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepository
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepository
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepository
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepository
import com.quran.shared.persistence.repository.note.repository.NotesRepository
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepository
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.currentPlatformDateTime
import com.quran.shared.persistence.util.toPlatform
import com.quran.shared.syncengine.AuthenticationDataFetcher
import com.quran.shared.syncengine.LocalModificationDateFetcher
import com.quran.shared.syncengine.SyncLifecycleGate
import com.quran.shared.syncengine.SynchronizationClient
import com.quran.shared.syncengine.SynchronizationEnvironment
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import kotlin.native.HiddenFromObjC
import kotlin.time.Instant

/**
 * Creates the scheduler-backed synchronization client used by [QuranDataService].
 *
 * Keeping this as a graph binding lets tests replace only the sync client creation path while
 * leaving the service and repository dependencies wired through Metro.
 */
@HiddenFromObjC
internal fun interface QuranDataServiceSynchronizationClientFactory {
    /**
     * Builds a synchronization client for the service lifecycle.
     *
     * @param pipeline the sync pipeline that owns resource adapters and repository bridges.
     * @param environment the remote sync environment.
     * @param localModificationDateFetcher reads the current local sync token.
     * @param authenticationDataFetcher supplies fresh authentication headers for sync requests.
     * @param syncLifecycleGate guards sync work during managed session resets.
     * @param callback receives sync completion and error events.
     * @return the synchronization client used for app-start, local-mutation, and immediate sync triggers.
     */
    fun create(
        pipeline: SyncEnginePipeline,
        environment: SynchronizationEnvironment,
        localModificationDateFetcher: LocalModificationDateFetcher,
        authenticationDataFetcher: AuthenticationDataFetcher,
        syncLifecycleGate: SyncLifecycleGate,
        callback: SyncEngineCallback
    ): SynchronizationClient
}

/**
 * Production sync client factory that delegates to [SyncEnginePipeline.setup].
 */
@HiddenFromObjC
internal class DefaultQuranDataServiceSynchronizationClientFactory @Inject constructor() :
    QuranDataServiceSynchronizationClientFactory {
    override fun create(
        pipeline: SyncEnginePipeline,
        environment: SynchronizationEnvironment,
        localModificationDateFetcher: LocalModificationDateFetcher,
        authenticationDataFetcher: AuthenticationDataFetcher,
        syncLifecycleGate: SyncLifecycleGate,
        callback: SyncEngineCallback
    ): SynchronizationClient =
        pipeline.setup(
            environment = environment,
            localModificationDateFetcher = localModificationDateFetcher,
            authenticationDataFetcher = authenticationDataFetcher,
            syncLifecycleGate = syncLifecycleGate,
            callback = callback
        )
}

/**
 * Managed app-facing data facade for Quran user data.
 *
 * Sync-capable clients use this facade for reads and writes so local persistence mutations,
 * sync scheduling, session reset safety, and cross-resource reconciliation stay coordinated.
 * This class must be obtained from the [com.quran.shared.pipeline.di.AppGraph] DI graph via
 * [com.quran.shared.pipeline.di.SharedDependencyGraph]. Direct construction is not supported.
 */
@Inject
@SingleIn(AppScope::class)
class QuranDataService internal constructor(
    private val authService: AuthService,
    private val pipeline: SyncEnginePipeline,
    private val environment: SynchronizationEnvironment,
    private val persistenceResetRepository: PersistenceResetRepository,
    private val persistenceImportRepository: PersistenceImportRepository,
    private val syncLocalModificationDateStore: SyncLocalModificationDateStore,
    private val sessionLifecycleCoordinator: SessionLifecycleCoordinator,
    private val syncClientFactory: QuranDataServiceSynchronizationClientFactory
) {
    private val serviceJob: Job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val syncClient: SynchronizationClient

    fun clear() {
        serviceJob.cancel()
        syncClient.cancelSyncing()
    }

    @HiddenFromObjC
    suspend fun clearAndJoin() {
        clear()
        serviceJob.cancelAndJoin()
        syncClient.cancelSyncingAndJoin()
    }

    @NativeCoroutinesState
    val authState: StateFlow<AuthState> get() = authService.authState

    // Cast the synchronization repository to the transactional repository.
    // This keeps QuranDataService as the single app-facing data entry point.
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

    /**
     * Flow of ayah highlights, stored independently from app-facing bookmark collections.
     */
    @NativeCoroutines
    val highlights: Flow<List<AyahHighlight>> get() = collectionBookmarksRepository.getHighlightsFlow()

    @NativeCoroutines
    val readingBookmark: Flow<ReadingBookmark?> get() = readingBookmarksRepository.getReadingBookmarkFlow()

    /**
     * Flow of all collections with their bookmarks for the UI to observe.
     *
     * The default collection is first. Non-default system collections remain hidden from this
     * app-facing collection list.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @NativeCoroutines
    val collectionsWithBookmarks: Flow<List<CollectionWithAyahBookmarks>>
        get() =
            collectionsRepository.getCollectionsFlow().flatMapLatest { collections ->
                val collectionFlows = collections
                    .filter { it.isDefault || !it.isSystem }
                    .sortedByDescending(Collection::isDefault)
                    .map { collection ->
                        collectionBookmarksRepository.getBookmarksForCollectionFlow(collection.id)
                            .map { bookmarks: List<CollectionAyahBookmark> ->
                                CollectionWithAyahBookmarks(collection, bookmarks)
                            }
                    }
                if (collectionFlows.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(collectionFlows) { it.toList() }
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

        syncClient = syncClientFactory.create(
            pipeline = pipeline,
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
        val remoteLogoutMode = RemoteLogoutMode.TOKEN_REVOCATION_ONLY
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
                logoutRemoteCleanupFailureWarnings(failure, remoteLogoutMode)
            } ?: try {
                authService.attemptRemoteLogout(
                    tokenMaterial!!,
                    remoteLogoutMode
                ).let { failures ->
                    failures.map { it.toLogoutWarning() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (failure: Throwable) {
                logoutRemoteCleanupFailureWarnings(failure, remoteLogoutMode)
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

    private fun logoutRemoteCleanupFailureWarnings(
        failure: Throwable,
        mode: RemoteLogoutMode
    ): List<LogoutWarning> =
        mode.operations.map { operation ->
            LogoutWarning(
                type = operation.toLogoutWarningType(),
                message = failure.message
            )
        }

    private fun RemoteLogoutFailure.toLogoutWarning(): LogoutWarning =
        LogoutWarning(
            type = operation.toLogoutWarningType(),
            message = exception.message
        )

    private fun RemoteLogoutOperation.toLogoutWarningType(): LogoutWarningType =
        when (this) {
            RemoteLogoutOperation.REVOKE_REFRESH_TOKEN -> LogoutWarningType.REVOKE_TOKEN_FAILED
            RemoteLogoutOperation.END_SESSION -> LogoutWarningType.END_SESSION_FAILED
        }

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
        return addBookmark(sura, ayah, currentPlatformDateTime())
    }

    @NativeCoroutines
    suspend fun addBookmark(sura: Int, ayah: Int, timestamp: PlatformDateTime): AyahBookmark {
        return mutatingCall("Failed to add ayah bookmark") {
            bookmarksRepository.addBookmark(sura, ayah, timestamp)
        }
    }

    /**
     * Sets the highlight color for one ayah, replacing any existing highlight color.
     * Default and user collection memberships are preserved.
     */
    @NativeCoroutines
    suspend fun setHighlight(sura: Int, ayah: Int, color: AyahHighlightColor): AyahHighlight {
        return setHighlight(sura, ayah, color, currentPlatformDateTime())
    }

    @NativeCoroutines
    suspend fun setHighlight(
        sura: Int,
        ayah: Int,
        color: AyahHighlightColor,
        timestamp: PlatformDateTime
    ): AyahHighlight {
        return mutatingCall("Failed to set ayah highlight") {
            collectionBookmarksRepository.setHighlight(sura, ayah, color, timestamp)
        }
    }

    /**
     * Removes the highlight from one ayah while preserving default and user collection memberships.
     *
     * @return `true` when at least one highlight membership was removed.
     */
    @NativeCoroutines
    suspend fun removeHighlight(sura: Int, ayah: Int): Boolean {
        return mutatingCall("Failed to remove ayah highlight", triggerAfter = false) {
            val removed = collectionBookmarksRepository.removeHighlight(
                sura,
                ayah,
                currentPlatformDateTime()
            )
            if (removed) {
                triggerSync()
            }
            removed
        }
    }

    @NativeCoroutines
    suspend fun addBookmark(sura: Int, ayah: Int, collectionIds: List<String>): AyahBookmark {
        return addBookmark(sura, ayah, collectionIds, currentPlatformDateTime())
    }

    @NativeCoroutines
    suspend fun addBookmark(
        sura: Int,
        ayah: Int,
        collectionIds: List<String>,
        timestamp: PlatformDateTime
    ): AyahBookmark {
        return mutatingCall("Failed to add ayah bookmark with collection memberships") {
            bookmarksRepository.addBookmark(sura, ayah, collectionIds, timestamp)
        }
    }

    @NativeCoroutines
    suspend fun replaceBookmarkCollections(id: String, collectionIds: List<String>): Boolean {
        return replaceBookmarkCollections(id, collectionIds, currentPlatformDateTime())
    }

    /**
     * Replaces the saved collection memberships for an existing ayah bookmark with an explicit
     * mutation timestamp and schedules sync only when memberships changed.
     */
    @NativeCoroutines
    suspend fun replaceBookmarkCollections(
        id: String,
        collectionIds: List<String>,
        timestamp: PlatformDateTime
    ): Boolean {
        return mutatingCall("Failed to replace bookmark collection memberships", triggerAfter = false) {
            val changed = bookmarksRepository.replaceBookmarkCollections(id, collectionIds, timestamp)
            if (changed) {
                triggerSync()
            }
            changed
        }
    }

    @NativeCoroutines
    suspend fun replaceAyahBookmarkCollections(
        sura: Int,
        ayah: Int,
        collectionIds: List<String>
    ): AyahBookmark {
        return replaceAyahBookmarkCollections(sura, ayah, collectionIds, currentPlatformDateTime())
    }

    /**
     * Creates an ayah bookmark if needed, then replaces its saved collection memberships with an
     * explicit mutation timestamp and schedules sync only when memberships changed.
     */
    @NativeCoroutines
    suspend fun replaceAyahBookmarkCollections(
        sura: Int,
        ayah: Int,
        collectionIds: List<String>,
        timestamp: PlatformDateTime
    ): AyahBookmark {
        return mutatingCall("Failed to replace ayah bookmark collection memberships", triggerAfter = false) {
            val result = bookmarksRepository.replaceAyahBookmarkCollections(sura, ayah, collectionIds, timestamp)
            if (result.changed) {
                triggerSync()
            }
            result.bookmark
        }
    }

    @NativeCoroutines
    suspend fun addReadingBookmark(sura: Int, ayah: Int): AyahReadingBookmark {
        return addAyahReadingBookmark(sura, ayah, currentPlatformDateTime())
    }

    @NativeCoroutines
    suspend fun addReadingBookmark(sura: Int, ayah: Int, timestamp: PlatformDateTime): AyahReadingBookmark {
        return addAyahReadingBookmark(sura, ayah, timestamp)
    }

    @NativeCoroutines
    suspend fun addAyahReadingBookmark(sura: Int, ayah: Int): AyahReadingBookmark {
        return addAyahReadingBookmark(sura, ayah, currentPlatformDateTime())
    }

    @NativeCoroutines
    suspend fun addAyahReadingBookmark(sura: Int, ayah: Int, timestamp: PlatformDateTime): AyahReadingBookmark {
        return mutatingCall("Failed to add reading ayah bookmark") {
            readingBookmarksRepository.addAyahReadingBookmark(sura, ayah, timestamp)
        }
    }

    @NativeCoroutines
    suspend fun addPageReadingBookmark(page: Int): PageReadingBookmark {
        return addPageReadingBookmark(page, currentPlatformDateTime())
    }

    @NativeCoroutines
    suspend fun addPageReadingBookmark(page: Int, timestamp: PlatformDateTime): PageReadingBookmark {
        return mutatingCall("Failed to add reading page bookmark") {
            readingBookmarksRepository.addPageReadingBookmark(page, timestamp)
        }
    }

    @NativeCoroutines
    suspend fun addReadingSession(sura: Int, ayah: Int): ReadingSession {
        return addReadingSession(sura, ayah, currentPlatformDateTime())
    }

    @NativeCoroutines
    suspend fun addReadingSession(sura: Int, ayah: Int, timestamp: PlatformDateTime): ReadingSession {
        return mutatingCall("Failed to add reading session") {
            readingSessionsRepository.addReadingSession(sura, ayah, timestamp)
        }
    }

    /**
     * Updates a reading session, merging it into an existing destination session when needed.
     *
     * The returned session is authoritative because a merge returns the destination's ID.
     */
    @NativeCoroutines
    suspend fun updateReadingSession(id: String, sura: Int, ayah: Int): ReadingSession {
        return updateReadingSession(id, sura, ayah, currentPlatformDateTime())
    }

    @NativeCoroutines
    suspend fun updateReadingSession(
        id: String,
        sura: Int,
        ayah: Int,
        timestamp: PlatformDateTime
    ): ReadingSession {
        return mutatingCall("Failed to update reading session") {
            readingSessionsRepository.updateReadingSession(id, sura, ayah, timestamp)
        }
    }

    /**
     * Deletes the reading session at the given ayah and schedules sync only when a row was removed.
     *
     * @param sura the sura number of the reading session to delete.
     * @param ayah the ayah number of the reading session to delete.
     * @return `true` when a reading session was deleted, or `false` when no matching session existed.
     */
    @NativeCoroutines
    suspend fun deleteReadingSession(sura: Int, ayah: Int): Boolean {
        return mutatingCall("Failed to delete reading session", triggerAfter = false) {
            val deleted = readingSessionsRepository.deleteReadingSession(sura, ayah)
            if (deleted) {
                triggerSync()
            }
            deleted
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
    suspend fun deleteBookmark(bookmark: AyahBookmark): Boolean {
        return deleteBookmarkResult("Failed to delete bookmark") {
            bookmarksRepository.deleteBookmark(bookmark)
        }
    }

    @NativeCoroutines
    suspend fun deleteBookmark(id: String): Boolean {
        return deleteBookmarkResult("Failed to delete bookmark") {
            bookmarksRepository.deleteBookmark(id)
        }
    }

    @NativeCoroutines
    suspend fun deleteBookmark(sura: Int, ayah: Int): Boolean {
        return deleteBookmarkResult("Failed to delete bookmark") {
            bookmarksRepository.deleteBookmark(sura, ayah)
        }
    }

    private suspend fun deleteBookmarkResult(
        errorMessage: String,
        block: suspend () -> Boolean
    ): Boolean {
        return mutatingCall(errorMessage, triggerAfter = false) {
            val deleted = block()
            if (deleted) {
                triggerSync()
            }
            deleted
        }
    }

    @NativeCoroutines
    suspend fun addCollection(name: String): Collection {
        return addCollection(name, currentPlatformDateTime())
    }

    @NativeCoroutines
    suspend fun addCollection(name: String, timestamp: PlatformDateTime): Collection {
        return mutatingCall("Failed to add collection") {
            collectionsRepository.addCollection(name, timestamp)
        }
    }

    /**
     * Updates a collection name and schedules sync for the local mutation.
     *
     * @param id the mobile-sync identifier of the collection to update.
     * @param name the new collection name.
     * @return the updated collection.
     * @throws IllegalArgumentException when [id] does not identify an active collection.
     */
    @NativeCoroutines
    suspend fun updateCollection(id: String, name: String): Collection {
        return updateCollection(id, name, currentPlatformDateTime())
    }

    /**
     * Updates a collection name with an explicit mutation timestamp and schedules sync.
     *
     * @param id the mobile-sync identifier of the collection to update.
     * @param name the new collection name.
     * @param timestamp the timestamp to persist for the mutation.
     * @return the updated collection.
     * @throws IllegalArgumentException when [id] does not identify an active collection.
     */
    @NativeCoroutines
    suspend fun updateCollection(id: String, name: String, timestamp: PlatformDateTime): Collection {
        return mutatingCall("Failed to update collection") {
            collectionsRepository.updateCollection(id, name, timestamp)
        }
    }

    /** Deletes a custom collection and schedules sync when a row was removed. */
    @NativeCoroutines
    suspend fun deleteCollection(id: String): Boolean {
        return mutatingCall("Failed to delete collection", triggerAfter = false) {
            val deleted = collectionsRepository.deleteCollection(id)
            if (deleted) {
                triggerSync()
            }
            deleted
        }
    }

    @NativeCoroutines
    suspend fun addBookmarkToCollection(collectionId: String, bookmark: AyahBookmark) {
        addBookmarkToCollection(collectionId, bookmark, currentPlatformDateTime())
    }

    @NativeCoroutines
    suspend fun addBookmarkToCollection(
        collectionId: String,
        bookmark: AyahBookmark,
        timestamp: PlatformDateTime
    ) {
        mutatingCall("Failed to add bookmark to collection") {
            collectionBookmarksRepository.addBookmarkToCollection(collectionId, bookmark, timestamp)
        }
    }

    @NativeCoroutines
    suspend fun addAyahBookmarkToCollection(
        collectionId: String,
        sura: Int,
        ayah: Int
    ): CollectionAyahBookmark {
        return addAyahBookmarkToCollection(collectionId, sura, ayah, currentPlatformDateTime())
    }

    @NativeCoroutines
    suspend fun addAyahBookmarkToCollection(
        collectionId: String,
        sura: Int,
        ayah: Int,
        timestamp: PlatformDateTime
    ): CollectionAyahBookmark {
        return mutatingCall("Failed to add ayah bookmark to collection") {
            collectionBookmarksRepository.addAyahBookmarkToCollection(collectionId, sura, ayah, timestamp)
        }
    }

    @NativeCoroutines
    suspend fun removeBookmarkFromCollection(collectionId: String, bookmark: AyahBookmark) {
        mutatingCall("Failed to remove bookmark from collection") {
            collectionBookmarksRepository.removeBookmarkFromCollection(collectionId, bookmark)
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
        addNote(body, startSura, startAyah, endSura, endAyah, currentPlatformDateTime())
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

    /**
     * Updates an existing note and schedules sync for the local mutation.
     *
     * @param id the mobile-sync identifier of the note to update.
     * @param body the updated note body.
     * @param startSura the first sura covered by the note.
     * @param startAyah the first ayah covered by the note.
     * @param endSura the last sura covered by the note.
     * @param endAyah the last ayah covered by the note.
     */
    @NativeCoroutines
    suspend fun updateNote(
        id: String,
        body: String,
        startSura: Int,
        startAyah: Int,
        endSura: Int,
        endAyah: Int
    ) {
        updateNote(id, body, startSura, startAyah, endSura, endAyah, currentPlatformDateTime())
    }

    @NativeCoroutines
    suspend fun updateNote(
        id: String,
        body: String,
        startSura: Int,
        startAyah: Int,
        endSura: Int,
        endAyah: Int,
        timestamp: PlatformDateTime
    ) {
        mutatingCall("Failed to update note") {
            notesRepository.updateNote(id, body, startSura, startAyah, endSura, endAyah, timestamp)
        }
    }

    @NativeCoroutines
    suspend fun deleteNote(id: String) {
        mutatingCall("Failed to delete note") {
            notesRepository.deleteNote(id)
        }
    }

    @NativeCoroutines
    fun getBookmarksForCollectionFlow(collectionId: String): Flow<List<CollectionAyahBookmark>> =
        collectionBookmarksRepository.getBookmarksForCollectionFlow(collectionId)

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
