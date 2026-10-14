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
import com.quran.shared.persistence.TextLengthLimitException
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.input.PersistenceImportResult
import com.quran.shared.persistence.model.AyahHighlight
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.BookmarkCollectionsReplacementResult
import com.quran.shared.persistence.model.Collection
import com.quran.shared.persistence.model.CollectionAyahBookmark
import com.quran.shared.persistence.model.CollectionWithAyahBookmarks
import com.quran.shared.persistence.model.Note
import com.quran.shared.persistence.model.ReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.persistence.model.ReadingSession
import com.quran.shared.persistence.repository.PersistenceResetRepository
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepository
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.currentPlatformDateTime
import com.quran.shared.persistence.util.toPlatform
import com.quran.shared.pipeline.sync.SyncAccount
import com.quran.shared.pipeline.sync.SyncClient
import com.quran.shared.pipeline.sync.SyncLifecycleGate
import com.quran.shared.pipeline.sync.SyncRunObserver
import com.quran.shared.pipeline.sync.SynchronizationClient
import com.quran.shared.syncv2.db.SyncV2Database
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
     * @param environment the remote sync environment.
     * @param account the signed-in session: request headers, the account id and forced token refreshes.
     * @param syncLifecycleGate guards sync work during managed session resets.
     * @param syncRunObserver receives the start and outcome of each sync run.
     * @return the synchronization client used for app-start, local-mutation, and immediate sync triggers.
     */
    fun create(
        environment: SynchronizationEnvironment,
        account: SyncAccount,
        syncLifecycleGate: SyncLifecycleGate,
        syncRunObserver: SyncRunObserver
    ): SynchronizationClient
}

/**
 * Production sync client factory: the sync engine over the app's database.
 */
@HiddenFromObjC
@Inject
internal class DefaultQuranDataServiceSynchronizationClientFactory(
    private val database: SyncV2Database
) : QuranDataServiceSynchronizationClientFactory {
    override fun create(
        environment: SynchronizationEnvironment,
        account: SyncAccount,
        syncLifecycleGate: SyncLifecycleGate,
        syncRunObserver: SyncRunObserver
    ): SynchronizationClient = SyncClient.create(database, environment, account, syncLifecycleGate, syncRunObserver)
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
    private val sessionLifecycleCoordinator: SessionLifecycleCoordinator,
    private val syncClientFactory: QuranDataServiceSynchronizationClientFactory
) {
    private val serviceJob: Job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val syncStatusTracker = SyncStatusTracker()
    private val syncClient: SynchronizationClient
    private val initializationMutex = Mutex()
    private var initializationAttempt: Deferred<Unit>? = null
    private var initialized = false
    private var syncLifecycleStarted = false

    /** Stops the service for good: its work, and the sync client's scheduler and HTTP client. */
    fun clear() {
        serviceJob.cancel()
        syncClient.cancelSyncing()
        (syncClient as? AutoCloseable)?.close()
    }

    @HiddenFromObjC
    suspend fun clearAndJoin() {
        clear()
        serviceJob.cancelAndJoin()
        syncClient.cancelSyncingAndJoin()
    }

    @HiddenFromObjC
    internal suspend fun awaitInitialization() {
        val attempt = initializationMutex.withLock {
            if (initialized) {
                null
            } else {
                initializationAttempt ?: scope.async {
                    sessionLifecycleCoordinator.completePersistedResetIfNeeded {
                        syncClient.cancelSyncingAndJoin()
                        resetLocalAuthDataAndToken()
                    }
                }.also { initializationAttempt = it }
            }
        } ?: return

        try {
            attempt.await()
        } catch (failure: Throwable) {
            if (attempt.isCompleted) {
                initializationMutex.withLock {
                    if (initializationAttempt === attempt) initializationAttempt = null
                }
            }
            throw failure
        }

        val shouldStartSyncLifecycle = initializationMutex.withLock {
            initialized = true
            if (initializationAttempt === attempt) initializationAttempt = null
            if (syncLifecycleStarted || !serviceJob.isActive) {
                false
            } else {
                syncLifecycleStarted = true
                true
            }
        }
        if (shouldStartSyncLifecycle) startSyncLifecycle()
    }

    private fun startSyncLifecycle() {
        scope.launch {
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

    @NativeCoroutinesState
    val authState: StateFlow<AuthState> get() = authService.authState

    /**
     * The state of the latest sync run. See [SyncStatus] for how retries, sign-out, and resets
     * are reported.
     */
    @NativeCoroutinesState
    val syncStatus: StateFlow<SyncStatus> get() = syncStatusTracker.status

    private val bookmarksRepository = pipeline.bookmarksRepository
    private val readingBookmarksRepository = pipeline.readingBookmarksRepository
    private val collectionsRepository = pipeline.collectionsRepository
    private val collectionBookmarksRepository = pipeline.collectionBookmarksRepository
    private val notesRepository = pipeline.notesRepository
    private val readingSessionsRepository = pipeline.readingSessionsRepository

    /**
     * Flow of ayah highlights, stored independently from app-facing bookmark collections.
     */
    @NativeCoroutines
    val highlights: Flow<List<AyahHighlight>> get() = collectionBookmarksRepository.getHighlightsFlow()

    @NativeCoroutines
    val readingBookmarks: Flow<List<ReadingBookmark>> get() = readingBookmarksRepository.getReadingBookmarksFlow()

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
     * Flow of all notes for the UI to observe. Notes archived on QuranReflect are not included.
     */
    @NativeCoroutines
    val notes: Flow<List<Note>> get() = notesRepository.getNotesFlow()

    /**
     * Flow of all reading sessions for the UI to observe.
     */
    @NativeCoroutines
    val readingSessions: Flow<List<ReadingSession>> get() = readingSessionsRepository.getReadingSessionsFlow()

    init {
        val account = object : SyncAccount {
            override suspend fun authHeaders(): Map<String, String> = authService.getAuthHeaders()

            override suspend fun accountId(): String? = authService.currentAccountId()

            override suspend fun forceTokenRefresh(): Boolean = authService.forceRefreshAccessToken()
        }

        syncClient = syncClientFactory.create(
            environment = environment,
            account = account,
            syncLifecycleGate = sessionLifecycleCoordinator,
            syncRunObserver = syncStatusTracker
        )

        scope.launch {
            try {
                awaitInitialization()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e) { "Failed to recover persisted session reset" }
            }
        }
    }

    @NativeCoroutines
    suspend fun logout(clearLocalData: Boolean = true): LogoutResult {
        awaitInitialization()
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

    /**
     * Schedules a sync.
     *
     * @param immediately `false` waits a few seconds so a burst of local edits syncs once. `true`
     *   starts right away, such as for a user's "Sync now". An immediate request made while a sync
     *   is running starts another right after it; one made while a sync waits to retry starts now.
     */
    fun triggerSync(immediately: Boolean = false) {
        if (!sessionLifecycleCoordinator.canStartSync()) {
            Logger.d { "Sync trigger skipped because session is resetting" }
            return
        }
        if (immediately) {
            syncClient.triggerSyncImmediately()
        } else {
            syncClient.localDataUpdated()
        }
    }

    private suspend fun resetLocalAuthDataAndToken() {
        authService.clearLocalSession()
        resetLocalDataAndSyncToken()
    }

    /** Deletes the user's data with its sync state: the token, the account, the bases and the outbox. */
    private suspend fun resetLocalDataAndSyncToken() {
        syncStatusTracker.reset()
        persistenceResetRepository.deleteAllData()
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
        shouldTriggerSync: (T) -> Boolean = { true },
        block: suspend () -> T
    ): T {
        try {
            awaitInitialization()
            return sessionLifecycleCoordinator.withMutatingWrite {
                val result = block()
                if (shouldTriggerSync(result)) {
                    triggerSync()
                }
                result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e) { errorMessage }
            throw e
        }
    }

    @NativeCoroutines
    suspend fun importData(data: PersistenceImportData): PersistenceImportResult {
        return importData(
            data = data,
            deleteExisting = false,
            trackHistory = false
        )
    }

    @NativeCoroutines
    suspend fun importData(
        data: PersistenceImportData,
        deleteExisting: Boolean
    ): PersistenceImportResult = importData(data, deleteExisting, trackHistory = false)

    @NativeCoroutines
    suspend fun importData(
        data: PersistenceImportData,
        deleteExisting: Boolean,
        trackHistory: Boolean
    ): PersistenceImportResult = mutatingCall(
        errorMessage = "Failed to import persistence data",
        shouldTriggerSync = { result -> result.changed }
    ) {
        persistenceImportRepository.importData(data, deleteExisting, trackHistory)
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
     * Deletes the highlight from one ayah while preserving default and user collection memberships.
     *
     * @return `true` when at least one highlight membership was removed.
     */
    @NativeCoroutines
    suspend fun deleteHighlight(sura: Int, ayah: Int): Boolean {
        return mutatingCall(
            errorMessage = "Failed to delete ayah highlight",
            shouldTriggerSync = { removed -> removed }
        ) {
            collectionBookmarksRepository.deleteHighlight(sura, ayah)
        }
    }

    @NativeCoroutines
    suspend fun replaceAyahBookmarkCollections(
        sura: Int,
        ayah: Int,
        collectionIds: List<String>
    ): BookmarkCollectionsReplacementResult {
        return replaceAyahBookmarkCollections(sura, ayah, collectionIds, currentPlatformDateTime())
    }

    /**
     * Replaces an ayah bookmark's saved collection memberships with an explicit mutation timestamp
     * and schedules sync only when memberships changed.
     *
     * Non-empty memberships create the bookmark if needed. Empty memberships remove the saved
     * bookmark, or do nothing when it does not exist. Highlight collection memberships are always
     * preserved and excluded from replacement.
     */
    @NativeCoroutines
    suspend fun replaceAyahBookmarkCollections(
        sura: Int,
        ayah: Int,
        collectionIds: List<String>,
        timestamp: PlatformDateTime
    ): BookmarkCollectionsReplacementResult {
        return mutatingCall(
            errorMessage = "Failed to replace ayah bookmark collection memberships",
            shouldTriggerSync = { result -> result.changed }
        ) {
            bookmarksRepository.replaceAyahBookmarkCollections(sura, ayah, collectionIds, timestamp)
        }
    }

    @NativeCoroutines
    suspend fun setAyahReadingBookmark(
        slot: ReadingBookmarkSlot,
        sura: Int,
        ayah: Int
    ): ReadingBookmark = setAyahReadingBookmark(slot, sura, ayah, currentPlatformDateTime())

    @NativeCoroutines
    suspend fun setAyahReadingBookmark(
        slot: ReadingBookmarkSlot,
        sura: Int,
        ayah: Int,
        timestamp: PlatformDateTime
    ): ReadingBookmark = mutatingCall("Failed to set reading ayah bookmark") {
        readingBookmarksRepository.setAyahReadingBookmark(slot, sura, ayah, timestamp)
    }

    @NativeCoroutines
    suspend fun setPageReadingBookmark(slot: ReadingBookmarkSlot, page: Int): ReadingBookmark =
        setPageReadingBookmark(slot, page, currentPlatformDateTime())

    @NativeCoroutines
    suspend fun setPageReadingBookmark(
        slot: ReadingBookmarkSlot,
        page: Int,
        timestamp: PlatformDateTime
    ): ReadingBookmark =
        mutatingCall("Failed to set reading page bookmark") {
            readingBookmarksRepository.setPageReadingBookmark(slot, page, timestamp)
        }

    @NativeCoroutines
    suspend fun renameReadingBookmark(slot: ReadingBookmarkSlot, name: String?): ReadingBookmark =
        renameReadingBookmark(slot, name, currentPlatformDateTime())

    @NativeCoroutines
    suspend fun renameReadingBookmark(
        slot: ReadingBookmarkSlot,
        name: String?,
        timestamp: PlatformDateTime
    ): ReadingBookmark =
        mutatingCall("Failed to rename reading bookmark") {
            readingBookmarksRepository.renameReadingBookmark(slot, name, timestamp)
        }

    @NativeCoroutines
    suspend fun clearReadingBookmark(slot: ReadingBookmarkSlot): ReadingBookmark =
        clearReadingBookmark(slot, currentPlatformDateTime())

    @NativeCoroutines
    suspend fun clearReadingBookmark(slot: ReadingBookmarkSlot, timestamp: PlatformDateTime): ReadingBookmark =
        mutatingCall("Failed to clear reading bookmark") {
            readingBookmarksRepository.clearReadingBookmark(slot, timestamp)
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
        return mutatingCall(
            errorMessage = "Failed to delete reading session",
            shouldTriggerSync = { deleted -> deleted }
        ) {
            readingSessionsRepository.deleteReadingSession(sura, ayah)
        }
    }

    /**
     * @throws com.quran.shared.persistence.ReservedCollectionNameException when [name] is reserved.
     * @throws TextLengthLimitException when [name] is outside the server's length limits.
     */
    @NativeCoroutines
    suspend fun addCollection(name: String): Collection {
        return addCollection(name, currentPlatformDateTime())
    }

    /**
     * @throws com.quran.shared.persistence.ReservedCollectionNameException when [name] is reserved.
     * @throws TextLengthLimitException when [name] is outside the server's length limits.
     */
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
     * @throws com.quran.shared.persistence.ReadOnlyCollectionException when the collection can't be renamed.
     * @throws com.quran.shared.persistence.DuplicateCollectionNameException when another collection has [name].
     * @throws com.quran.shared.persistence.ReservedCollectionNameException when [name] is reserved.
     * @throws TextLengthLimitException when [name] is outside the server's length limits.
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
     * @throws com.quran.shared.persistence.ReadOnlyCollectionException when the collection can't be renamed.
     * @throws com.quran.shared.persistence.DuplicateCollectionNameException when another collection has [name].
     * @throws com.quran.shared.persistence.ReservedCollectionNameException when [name] is reserved.
     * @throws TextLengthLimitException when [name] is outside the server's length limits.
     */
    @NativeCoroutines
    suspend fun updateCollection(id: String, name: String, timestamp: PlatformDateTime): Collection {
        return mutatingCall("Failed to update collection") {
            collectionsRepository.updateCollection(id, name, timestamp)
        }
    }

    /**
     * Deletes a custom collection and schedules sync when a row was removed.
     *
     * @throws com.quran.shared.persistence.ReadOnlyCollectionException when the collection can't be deleted.
     */
    @NativeCoroutines
    suspend fun deleteCollection(id: String): Boolean {
        return mutatingCall(
            errorMessage = "Failed to delete collection",
            shouldTriggerSync = { deleted -> deleted }
        ) {
            collectionsRepository.deleteCollection(id)
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

    /** Removes an ayah bookmark from a collection and schedules sync when a membership was removed. */
    @NativeCoroutines
    suspend fun removeAyahBookmarkFromCollection(collectionId: String, bookmarkId: String): Boolean {
        return mutatingCall(
            errorMessage = "Failed to remove bookmark from collection",
            shouldTriggerSync = { removed -> removed }
        ) {
            collectionBookmarksRepository.removeAyahBookmarkFromCollection(collectionId, bookmarkId)
        }
    }

    /** @throws TextLengthLimitException when [body] is outside the server's length limits. */
    @NativeCoroutines
    suspend fun addNote(body: String, startSura: Int, startAyah: Int, endSura: Int, endAyah: Int) {
        addNote(body, startSura, startAyah, endSura, endAyah, currentPlatformDateTime())
    }

    /** @throws TextLengthLimitException when [body] is outside the server's length limits. */
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
     * Updates a note's body and schedules sync for the local mutation. A note's verse ranges never
     * change, as on the server.
     *
     * @param id the mobile-sync identifier of the note to update.
     * @param body the updated note body.
     * @throws IllegalArgumentException when [id] does not identify a note.
     * @throws TextLengthLimitException when a changed [body] is outside the server's length limits.
     */
    @NativeCoroutines
    suspend fun updateNote(id: String, body: String) {
        updateNote(id, body, currentPlatformDateTime())
    }

    /** @throws TextLengthLimitException when a changed [body] is outside the server's length limits. */
    @NativeCoroutines
    suspend fun updateNote(id: String, body: String, timestamp: PlatformDateTime) {
        mutatingCall("Failed to update note") {
            notesRepository.updateNote(id, body, timestamp)
        }
    }

    /** Deletes a note and schedules sync when a row was removed. */
    @NativeCoroutines
    suspend fun deleteNote(id: String): Boolean {
        return mutatingCall(
            errorMessage = "Failed to delete note",
            shouldTriggerSync = { deleted -> deleted }
        ) {
            notesRepository.deleteNote(id)
        }
    }

}
