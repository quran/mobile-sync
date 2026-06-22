@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.quran.shared.pipeline

import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.model.AuthRuntimeConfig
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.LogoutTokenMaterial
import com.quran.shared.auth.repository.RemoteLogoutFailure
import com.quran.shared.auth.repository.RemoteLogoutOperation
import com.quran.shared.auth.service.AuthSessionPublicationGuard
import com.quran.shared.auth.service.AuthService
import com.quran.shared.auth.di.AuthModule
import com.quran.shared.di.AppScope
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.LocalMutationAck
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.input.PersistenceImportResult
import com.quran.shared.persistence.input.LocalSyncCollection
import com.quran.shared.persistence.input.LocalSyncCollectionAyahBookmark
import com.quran.shared.persistence.input.LocalSyncNote
import com.quran.shared.persistence.input.LocalSyncReadingSession
import com.quran.shared.persistence.input.RemoteBookmark
import com.quran.shared.persistence.input.RemoteCollection
import com.quran.shared.persistence.input.RemoteCollectionBookmark
import com.quran.shared.persistence.input.RemoteNote
import com.quran.shared.persistence.input.RemoteReadingSession
import com.quran.shared.persistence.di.PersistenceModule
import com.quran.shared.persistence.model.AyahBookmark
import com.quran.shared.persistence.model.AyahReadingBookmark
import com.quran.shared.persistence.model.BookmarkCollectionsReplacementResult
import com.quran.shared.persistence.model.Collection
import com.quran.shared.persistence.model.CollectionAyahBookmark
import com.quran.shared.persistence.model.Note
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmark
import com.quran.shared.persistence.model.ReadingSession
import com.quran.shared.persistence.repository.PersistenceWriteBoundaryGuard
import com.quran.shared.persistence.repository.PersistenceResetRepository
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepository
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksSynchronizationRepository
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepository
import com.quran.shared.persistence.repository.collection.repository.CollectionsSynchronizationRepository
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepository
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksSynchronizationRepository
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepository
import com.quran.shared.persistence.repository.note.repository.NotesRepository
import com.quran.shared.persistence.repository.note.repository.NotesSynchronizationRepository
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepository
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepository
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsSynchronizationRepository
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.toPlatform
import com.quran.shared.syncengine.SynchronizationClient
import com.quran.shared.syncengine.SynchronizationEnvironment
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import com.quran.shared.pipeline.storage.MobileSyncStorageModule
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createDynamicGraphFactory
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class QuranDataServiceLifecycleTest {
    private val dispatcher = StandardTestDispatcher()
    private val fixtures = mutableListOf<QuranDataServiceFixture>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        runTest(dispatcher) {
            fixtures.forEach { it.clearAndJoin() }
            fixtures.clear()
            advanceUntilIdle()
        }
        Dispatchers.resetMain()
    }

    private fun quranDataServiceFixture(
        authRepository: ServiceAuthRepository = ServiceAuthRepository(),
        resetRepository: ServiceResetRepository = ServiceResetRepository(),
        lifecycleStore: SettingsSessionLifecycleStateStore =
            SettingsSessionLifecycleStateStore(MapSettings().toSuspendSettings()),
        useRecordingSyncClient: Boolean = false
    ): QuranDataServiceFixture =
        QuranDataServiceFixture(
            authRepository = authRepository,
            resetRepository = resetRepository,
            lifecycleStore = lifecycleStore,
            useRecordingSyncClient = useRecordingSyncClient
        ).also { fixtures += it }

    @Test
    fun `clearLocalData false throws exact unsupported message`() = runTest(dispatcher) {
        val fixture = quranDataServiceFixture()
        advanceUntilIdle()

        val error = assertFailsWith<UnsupportedOperationException> {
            fixture.service.logout(clearLocalData = false)
        }

        assertEquals("Keep-local logout is not implemented yet", error.message)
        fixture.clearAndJoin()
    }

    @Test
    fun `clearAndJoin cancels auth startup work before returning`() = runTest(dispatcher) {
        val fixture = quranDataServiceFixture(
            authRepository = ServiceAuthRepository(
                accessToken = "access-token",
                blockFirstRefresh = true
            )
        )
        advanceUntilIdle()
        withContext(Dispatchers.Default) {
            withTimeout(1_000) {
                fixture.authRepository.firstRefreshStarted.await()
            }
        }

        try {
            fixture.clearAndJoin()

            assertEquals(true, fixture.authRepository.firstRefreshCancelled.isCompleted)
        } finally {
            fixture.authRepository.firstRefreshCanFinish.complete(Unit)
            advanceUntilIdle()
        }
    }

    @Test
    fun `remote logout failures return warnings after local auth data and token are cleared`() = runTest(dispatcher) {
        val fixture = quranDataServiceFixture(
            authRepository = ServiceAuthRepository(
                refreshToken = "refresh-token",
                idToken = "id-token",
                remoteFailures = listOf(
                    RemoteLogoutFailure(RemoteLogoutOperation.REVOKE_REFRESH_TOKEN, Exception("revoke failed")),
                    RemoteLogoutFailure(RemoteLogoutOperation.END_SESSION, Exception("end-session failed"))
                )
            )
        )
        fixture.tokenStore.updateLastModificationDate(77L)
        advanceUntilIdle()

        val result = fixture.service.logout()

        assertEquals(
            listOf(
                LogoutWarning(LogoutWarningType.REVOKE_TOKEN_FAILED, "revoke failed"),
                LogoutWarning(LogoutWarningType.END_SESSION_FAILED, "end-session failed")
            ),
            result.warnings
        )
        assertEquals(null, fixture.authRepository.getAccessToken())
        assertEquals(1, fixture.resetRepository.deleteCount)
        assertEquals(0L, fixture.tokenStore.localLastModificationDate())
        fixture.clearAndJoin()
    }

    @Test
    fun `managed logout clears published auth before waiting for sync drain`() = runTest(dispatcher) {
        val fixture = quranDataServiceFixture(
            authRepository = ServiceAuthRepository(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                idToken = "id-token",
                blockFirstAuthHeaders = true
            )
        )

        advanceUntilIdle()
        withContext(kotlinx.coroutines.Dispatchers.Default) {
            withTimeout(1_000) {
                fixture.authRepository.firstAuthHeadersStarted.await()
            }
        }

        val logoutJob = launch {
            fixture.service.logout()
        }
        try {
            advanceUntilIdle()

            fixture.awaitResetInProgress()
            assertEquals(true, fixture.lifecycleStore.snapshot().resetInProgress)
            withContext(Dispatchers.Default) {
                withTimeout(1_000) {
                    while (fixture.service.authState.value != AuthState.Idle) {
                        yield()
                    }
                }
            }
            assertEquals(AuthState.Idle, fixture.service.authState.value)
            assertEquals(false, fixture.authService.isLoggedIn())
            assertEquals(null, fixture.authService.getAccessToken())
            assertEquals(emptyMap(), fixture.authService.getAuthHeaders())
            assertEquals(false, logoutJob.isCompleted)
        } finally {
            fixture.authRepository.firstAuthHeadersCanFinish.complete(Unit)
        }

        logoutJob.join()
        advanceUntilIdle()

        assertEquals(1, fixture.resetRepository.deleteCount)
        assertEquals(0L, fixture.tokenStore.localLastModificationDate())
        fixture.clearAndJoin()
    }

    @Test
    fun `managed logout clears published auth before waiting for active mutating write drain`() = runTest(dispatcher) {
        val activeWriteCanFinish = CompletableDeferred<Unit>()
        val activeWriteStarted = CompletableDeferred<Unit>()
        val fixture = quranDataServiceFixture(
            authRepository = ServiceAuthRepository(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                idToken = "id-token"
            )
        )
        advanceUntilIdle()
        fixture.authService.refreshAccessTokenIfNeeded()
        assertEquals(true, fixture.authService.isLoggedIn())

        val activeWrite = launch {
            fixture.lifecycleCoordinator.withMutatingWrite {
                activeWriteStarted.complete(Unit)
                activeWriteCanFinish.await()
            }
        }
        activeWriteStarted.await()

        val logoutJob = launch {
            fixture.service.logout()
        }
        advanceUntilIdle()

        fixture.awaitResetInProgress()
        assertEquals(true, fixture.lifecycleStore.snapshot().resetInProgress)
        withContext(Dispatchers.Default) {
            withTimeout(1_000) {
                while (fixture.service.authState.value != AuthState.Idle) {
                    yield()
                }
            }
        }
        assertEquals(AuthState.Idle, fixture.service.authState.value)
        assertEquals(false, fixture.authService.isLoggedIn())
        assertEquals(null, fixture.authService.getAccessToken())
        assertEquals(emptyMap(), fixture.authService.getAuthHeaders())
        assertEquals(0, fixture.resetRepository.deleteCount)
        assertEquals(false, logoutJob.isCompleted)

        activeWriteCanFinish.complete(Unit)
        activeWrite.join()
        logoutJob.join()
        advanceUntilIdle()

        assertEquals(1, fixture.resetRepository.deleteCount)
        fixture.clearAndJoin()
    }

    @Test
    fun `remote logout cleanup runs only after local data auth and sync token are cleared`() = runTest(dispatcher) {
        lateinit var fixture: QuranDataServiceFixture
        fixture = quranDataServiceFixture(
            authRepository = ServiceAuthRepository(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                idToken = "id-token",
                onAttemptRemoteLogout = {
                    assertEquals(null, fixture.authRepository.getAccessToken())
                    assertEquals(1, fixture.resetRepository.deleteCount)
                    assertEquals(0L, fixture.tokenStore.localLastModificationDate())
                }
            )
        )
        fixture.tokenStore.updateLastModificationDate(77L)
        advanceUntilIdle()

        fixture.service.logout()

        assertEquals(1, fixture.authRepository.remoteLogoutAttemptCount)
        fixture.clearAndJoin()
    }

    @Test
    fun `sync auth facade logout uses managed reset coordinator`() = runTest(dispatcher) {
        val fixture = quranDataServiceFixture(
            authRepository = ServiceAuthRepository(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                idToken = "id-token"
            )
        )
        val authFacade = SyncAuthService(fixture.authService, fixture.service, fixture.lifecycleCoordinator)
        fixture.tokenStore.updateLastModificationDate(77L)
        advanceUntilIdle()

        val result = authFacade.logout()

        assertEquals(emptyList(), result.warnings)
        assertEquals(null, fixture.authRepository.getAccessToken())
        assertEquals(1, fixture.resetRepository.deleteCount)
        assertEquals(0L, fixture.tokenStore.localLastModificationDate())
        fixture.clearAndJoin()
    }

    @Test
    fun `sync auth facade reports whether authentication is configured`() = runTest(dispatcher) {
        val fixture = quranDataServiceFixture()

        val unconfiguredAuthFacade = SyncAuthService(
            fixture.authService,
            fixture.service,
            fixture.lifecycleCoordinator
        )
        val configuredAuthFacade = SyncAuthService(
            fixture.authService,
            fixture.service,
            fixture.lifecycleCoordinator,
            AuthRuntimeConfig.Configured(AuthConfig(clientId = "client-id"))
        )

        assertFalse(unconfiguredAuthFacade.isAuthenticationConfigured)
        assertTrue(configuredAuthFacade.isAuthenticationConfigured)
        fixture.clearAndJoin()
    }

    @Test
    fun `sync auth facade login throws during reset before committing tokens`() = runTest(dispatcher) {
        val fixture = quranDataServiceFixture()
        val authFacade = SyncAuthService(fixture.authService, fixture.service, fixture.lifecycleCoordinator)
        advanceUntilIdle()

        fixture.lifecycleCoordinator.runManagedReset {
            assertFailsWith<SessionResetInProgressException> {
                authFacade.login()
            }
        }

        assertEquals(null, fixture.authRepository.getAccessToken())
        assertEquals(0, fixture.authRepository.loginCalls)
        fixture.clearAndJoin()
    }

    @Test
    fun `sync auth facade reauthentication throws during reset before committing tokens`() = runTest(dispatcher) {
        val fixture = quranDataServiceFixture()
        val authFacade = SyncAuthService(fixture.authService, fixture.service, fixture.lifecycleCoordinator)
        advanceUntilIdle()

        fixture.lifecycleCoordinator.runManagedReset {
            assertFailsWith<SessionResetInProgressException> {
                authFacade.loginWithReauthentication()
            }
        }

        assertEquals(null, fixture.authRepository.getAccessToken())
        assertEquals(0, fixture.authRepository.reauthenticationCalls)
        fixture.clearAndJoin()
    }

    @Test
    fun `remote logout cancellation propagates instead of returning warning`() = runTest(dispatcher) {
        val fixture = quranDataServiceFixture(
            authRepository = ServiceAuthRepository(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                idToken = "id-token",
                remoteLogoutException = CancellationException("remote logout cancelled")
            )
        )
        fixture.tokenStore.updateLastModificationDate(77L)
        advanceUntilIdle()

        assertFailsWith<CancellationException> {
            fixture.service.logout()
        }

        assertEquals(null, fixture.authRepository.getAccessToken())
        assertEquals(1, fixture.resetRepository.deleteCount)
        assertEquals(0L, fixture.tokenStore.localLastModificationDate())
        fixture.clearAndJoin()
    }

    @Test
    fun `logout token capture failure still clears local auth data and token`() = runTest(dispatcher) {
        val fixture = quranDataServiceFixture(
            authRepository = ServiceAuthRepository(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                idToken = "id-token",
                throwOnLogoutTokenCapture = true
            )
        )
        fixture.tokenStore.updateLastModificationDate(77L)
        advanceUntilIdle()

        val result = fixture.service.logout()

        assertEquals(
            listOf(
                LogoutWarning(LogoutWarningType.REVOKE_TOKEN_FAILED, "token capture failed"),
                LogoutWarning(LogoutWarningType.END_SESSION_FAILED, "token capture failed")
            ),
            result.warnings
        )
        assertEquals(null, fixture.authRepository.getAccessToken())
        assertEquals(1, fixture.resetRepository.deleteCount)
        assertEquals(0L, fixture.tokenStore.localLastModificationDate())
        fixture.clearAndJoin()
    }

    @Test
    fun `startup recovery completes persisted reset before normal use`() = runTest(dispatcher) {
        val settings = MapSettings().toSuspendSettings()
        val lifecycleStore = SettingsSessionLifecycleStateStore(settings)
        lifecycleStore.beginReset()
        val fixture = quranDataServiceFixture(lifecycleStore = lifecycleStore)

        repeat(10) {
            advanceUntilIdle()
            if (!lifecycleStore.snapshot().resetInProgress) {
                return@repeat
            }
            yield()
        }

        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = false), lifecycleStore.snapshot())
        assertEquals(1, fixture.resetRepository.deleteCount)
        assertEquals(0L, fixture.tokenStore.localLastModificationDate())
        fixture.clearAndJoin()
    }

    @Test
    fun `startup persisted reset suppresses stale stored auth publication`() = runTest(dispatcher) {
        val settings = MapSettings().toSuspendSettings()
        val lifecycleStore = SettingsSessionLifecycleStateStore(settings)
        lifecycleStore.beginReset()
        val fixture = quranDataServiceFixture(
            authRepository = ServiceAuthRepository(
                accessToken = "stale-access-token",
                refreshToken = "stale-refresh-token",
                idToken = "stale-id-token"
            ),
            lifecycleStore = lifecycleStore
        )

        repeat(10) {
            advanceUntilIdle()
            if (!lifecycleStore.snapshot().resetInProgress) {
                return@repeat
            }
            yield()
        }

        assertEquals(AuthState.Idle, fixture.service.authState.value)
        assertEquals(false, fixture.authService.isLoggedIn())
        assertEquals(null, fixture.authService.getAccessToken())
        assertEquals(null, fixture.authRepository.getAccessToken())
        assertEquals(1, fixture.resetRepository.deleteCount)
        fixture.clearAndJoin()
    }

    @Test
    fun `mutating calls throw during reset and logged out writes are allowed after reset`() = runTest(dispatcher) {
        val fixture = quranDataServiceFixture()
        advanceUntilIdle()

        fixture.lifecycleCoordinator.runManagedReset {
            assertFailsWith<SessionResetInProgressException> {
                fixture.service.addBookmark(2, 255)
            }
        }

        fixture.service.addBookmark(2, 255)

        assertEquals(1, fixture.bookmarksRepository.addCount)
        fixture.clearAndJoin()
    }

    @Test
    fun `updateCollection delegates to repository and triggers local sync`() = runTest(dispatcher) {
        val fixture = quranDataServiceFixture(useRecordingSyncClient = true)
        advanceUntilIdle()

        val result = fixture.service.updateCollection("collection-local-id", "Updated collection")

        assertEquals(Collection("Updated collection", testTimestamp(), "collection-local-id"), result)
        assertEquals(
            listOf(CollectionUpdateCall("collection-local-id", "Updated collection", null)),
            fixture.collectionsRepository.updateCalls
        )
        assertEquals(1, fixture.syncClient.localDataUpdatedCount)
        fixture.clearAndJoin()
    }

    @Test
    fun `updateCollection with timestamp delegates to repository and triggers local sync`() = runTest(dispatcher) {
        val fixture = quranDataServiceFixture(useRecordingSyncClient = true)
        val timestamp = Instant.fromEpochMilliseconds(42).toPlatform()
        advanceUntilIdle()

        val result = fixture.service.updateCollection("collection-local-id", "Updated collection", timestamp)

        assertEquals(Collection("Updated collection", timestamp, "collection-local-id"), result)
        assertEquals(
            listOf(CollectionUpdateCall("collection-local-id", "Updated collection", timestamp)),
            fixture.collectionsRepository.updateCalls
        )
        assertEquals(1, fixture.syncClient.localDataUpdatedCount)
        fixture.clearAndJoin()
    }

    @Test
    fun `deleteReadingSession returns false without triggering sync when nothing is deleted`() = runTest(dispatcher) {
        val fixture = quranDataServiceFixture(useRecordingSyncClient = true)
        fixture.readingSessionsRepository.deleteResult = false
        advanceUntilIdle()

        val result = fixture.service.deleteReadingSession(2, 255)

        assertFalse(result)
        assertEquals(listOf(ReadingSessionDeleteCall(2, 255)), fixture.readingSessionsRepository.deleteCalls)
        assertEquals(0, fixture.syncClient.localDataUpdatedCount)
        fixture.clearAndJoin()
    }

    @Test
    fun `deleteReadingSession returns true and triggers sync when deletion succeeds`() = runTest(dispatcher) {
        val fixture = quranDataServiceFixture(useRecordingSyncClient = true)
        fixture.readingSessionsRepository.deleteResult = true
        advanceUntilIdle()

        val result = fixture.service.deleteReadingSession(2, 255)

        assertTrue(result)
        assertEquals(listOf(ReadingSessionDeleteCall(2, 255)), fixture.readingSessionsRepository.deleteCalls)
        assertEquals(1, fixture.syncClient.localDataUpdatedCount)
        fixture.clearAndJoin()
    }

    @Test
    fun `addCollection returns repository collection and triggers local sync`() = runTest(dispatcher) {
        val fixture = quranDataServiceFixture(useRecordingSyncClient = true)
        advanceUntilIdle()

        val result = fixture.service.addCollection("Favorites")

        assertEquals(Collection("Favorites", testTimestamp(), "collection-1"), result)
        assertEquals(listOf(CollectionAddCall("Favorites", null)), fixture.collectionsRepository.addCalls)
        assertEquals(1, fixture.syncClient.localDataUpdatedCount)
        fixture.clearAndJoin()
    }

    @Test
    fun `addCollection with timestamp returns repository collection and triggers local sync`() = runTest(dispatcher) {
        val fixture = quranDataServiceFixture(useRecordingSyncClient = true)
        val timestamp = Instant.fromEpochMilliseconds(42).toPlatform()
        advanceUntilIdle()

        val result = fixture.service.addCollection("Favorites", timestamp)

        assertEquals(Collection("Favorites", timestamp, "collection-1"), result)
        assertEquals(listOf(CollectionAddCall("Favorites", timestamp)), fixture.collectionsRepository.addCalls)
        assertEquals(1, fixture.syncClient.localDataUpdatedCount)
        fixture.clearAndJoin()
    }

    @Test
    fun `deleteBookmark by local id returns false without triggering sync when nothing is deleted`() =
        runTest(dispatcher) {
            val fixture = quranDataServiceFixture(useRecordingSyncClient = true)
            fixture.bookmarksRepository.deleteResult = false
            advanceUntilIdle()

            val result = fixture.service.deleteBookmark("bookmark-local-id")

            assertFalse(result)
            assertEquals(
                listOf<BookmarkDeleteCall>(BookmarkLocalIdDeleteCall("bookmark-local-id")),
                fixture.bookmarksRepository.deleteCalls
            )
            assertEquals(0, fixture.syncClient.localDataUpdatedCount)
            fixture.clearAndJoin()
        }

    @Test
    fun `deleteBookmark by sura and ayah returns true and triggers sync when deletion succeeds`() =
        runTest(dispatcher) {
            val fixture = quranDataServiceFixture(useRecordingSyncClient = true)
            fixture.bookmarksRepository.deleteResult = true
            advanceUntilIdle()

            val result = fixture.service.deleteBookmark(2, 255)

            assertTrue(result)
            assertEquals(
                listOf<BookmarkDeleteCall>(BookmarkAyahDeleteCall(2, 255)),
                fixture.bookmarksRepository.deleteCalls
            )
            assertEquals(1, fixture.syncClient.localDataUpdatedCount)
            fixture.clearAndJoin()
        }

    @Test
    fun `deleteCollection returns false without triggering sync when nothing is deleted`() = runTest(dispatcher) {
        val fixture = quranDataServiceFixture(useRecordingSyncClient = true)
        fixture.collectionsRepository.deleteResult = false
        advanceUntilIdle()

        val result = fixture.service.deleteCollection("collection-local-id")

        assertFalse(result)
        assertEquals(listOf(CollectionDeleteCall("collection-local-id")), fixture.collectionsRepository.deleteCalls)
        assertEquals(0, fixture.syncClient.localDataUpdatedCount)
        fixture.clearAndJoin()
    }

    @Test
    fun `deleteCollection returns true and triggers sync when deletion succeeds`() = runTest(dispatcher) {
        val fixture = quranDataServiceFixture(useRecordingSyncClient = true)
        fixture.collectionsRepository.deleteResult = true
        advanceUntilIdle()

        val result = fixture.service.deleteCollection("collection-local-id")

        assertTrue(result)
        assertEquals(listOf(CollectionDeleteCall("collection-local-id")), fixture.collectionsRepository.deleteCalls)
        assertEquals(1, fixture.syncClient.localDataUpdatedCount)
        fixture.clearAndJoin()
    }

    @Test
    fun `replaceBookmarkCollections returns false without triggering sync when memberships are unchanged`() =
        runTest(dispatcher) {
            val fixture = quranDataServiceFixture(useRecordingSyncClient = true)
            fixture.bookmarksRepository.replaceResult = false
            advanceUntilIdle()

            val result = fixture.service.replaceBookmarkCollections("bookmark-local-id", listOf("collection-a"))

            assertFalse(result)
            assertEquals(
                listOf(BookmarkCollectionsReplaceCall("bookmark-local-id", listOf("collection-a"))),
                fixture.bookmarksRepository.replaceCalls
            )
            assertEquals(0, fixture.syncClient.localDataUpdatedCount)
            fixture.clearAndJoin()
        }

    @Test
    fun `replaceBookmarkCollections returns true and triggers sync when memberships change`() =
        runTest(dispatcher) {
            val fixture = quranDataServiceFixture(useRecordingSyncClient = true)
            fixture.bookmarksRepository.replaceResult = true
            advanceUntilIdle()

            val result = fixture.service.replaceBookmarkCollections("bookmark-local-id", listOf("collection-a"))

            assertTrue(result)
            assertEquals(
                listOf(BookmarkCollectionsReplaceCall("bookmark-local-id", listOf("collection-a"))),
                fixture.bookmarksRepository.replaceCalls
            )
            assertEquals(1, fixture.syncClient.localDataUpdatedCount)
            fixture.clearAndJoin()
        }

    @Test
    fun `replaceBookmarkCollections with timestamp delegates and triggers sync when memberships change`() =
        runTest(dispatcher) {
            val fixture = quranDataServiceFixture(useRecordingSyncClient = true)
            val timestamp = Instant.fromEpochMilliseconds(42).toPlatform()
            fixture.bookmarksRepository.replaceResult = true
            advanceUntilIdle()

            val result = fixture.service.replaceBookmarkCollections(
                localId = "bookmark-local-id",
                collectionLocalIds = listOf("collection-a"),
                timestamp = timestamp
            )

            assertTrue(result)
            assertEquals(
                listOf(BookmarkCollectionsReplaceCall("bookmark-local-id", listOf("collection-a"), timestamp)),
                fixture.bookmarksRepository.replaceCalls
            )
            assertEquals(1, fixture.syncClient.localDataUpdatedCount)
            fixture.clearAndJoin()
        }

    @Test
    fun `replaceAyahBookmarkCollections returns bookmark and triggers sync only when memberships change`() =
        runTest(dispatcher) {
            val fixture = quranDataServiceFixture(useRecordingSyncClient = true)
            fixture.bookmarksRepository.replaceAyahResultChanged = true
            advanceUntilIdle()

            val result = fixture.service.replaceAyahBookmarkCollections(2, 255, listOf("collection-a"))

            assertEquals(AyahBookmark(2, 255, testTimestamp(), "bookmark-replaced"), result)
            assertEquals(
                listOf(BookmarkAyahCollectionsReplaceCall(2, 255, listOf("collection-a"))),
                fixture.bookmarksRepository.replaceAyahCalls
            )
            assertEquals(1, fixture.syncClient.localDataUpdatedCount)
            fixture.clearAndJoin()
        }

    @Test
    fun `replaceAyahBookmarkCollections returns bookmark without triggering sync when memberships are unchanged`() =
        runTest(dispatcher) {
            val fixture = quranDataServiceFixture(useRecordingSyncClient = true)
            fixture.bookmarksRepository.replaceAyahResultChanged = false
            advanceUntilIdle()

            val result = fixture.service.replaceAyahBookmarkCollections(2, 255, listOf("collection-a"))

            assertEquals(AyahBookmark(2, 255, testTimestamp(), "bookmark-replaced"), result)
            assertEquals(0, fixture.syncClient.localDataUpdatedCount)
            fixture.clearAndJoin()
        }

    @Test
    fun `replaceAyahBookmarkCollections with timestamp delegates and skips sync when memberships are unchanged`() =
        runTest(dispatcher) {
            val fixture = quranDataServiceFixture(useRecordingSyncClient = true)
            val timestamp = Instant.fromEpochMilliseconds(42).toPlatform()
            fixture.bookmarksRepository.replaceAyahResultChanged = false
            advanceUntilIdle()

            val result = fixture.service.replaceAyahBookmarkCollections(
                sura = 2,
                ayah = 255,
                collectionLocalIds = listOf("collection-a"),
                timestamp = timestamp
            )

            assertEquals(AyahBookmark(2, 255, timestamp, "bookmark-replaced"), result)
            assertEquals(
                listOf(BookmarkAyahCollectionsReplaceCall(2, 255, listOf("collection-a"), timestamp)),
                fixture.bookmarksRepository.replaceAyahCalls
            )
            assertEquals(0, fixture.syncClient.localDataUpdatedCount)
            fixture.clearAndJoin()
        }

    @Test
    fun `reset failure leaves marker active and blocks mutating writes`() = runTest(dispatcher) {
        val resetRepository = ServiceResetRepository(failDelete = true)
        val fixture = quranDataServiceFixture(resetRepository = resetRepository)
        advanceUntilIdle()

        assertFailsWith<IllegalStateException> {
            fixture.service.logout()
        }

        assertEquals(true, fixture.lifecycleStore.snapshot().resetInProgress)
        assertFailsWith<SessionResetInProgressException> {
            fixture.service.addBookmark(2, 255)
        }
        fixture.clearAndJoin()
    }

    @Test
    fun `local auth clear failure aborts logout and leaves reset marker active`() = runTest(dispatcher) {
        val fixture = quranDataServiceFixture(
            authRepository = ServiceAuthRepository(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                idToken = "id-token",
                throwOnClearLocalSession = true
            )
        )
        fixture.tokenStore.updateLastModificationDate(77L)
        advanceUntilIdle()

        val error = assertFailsWith<IllegalStateException> {
            fixture.service.logout()
        }

        assertEquals("local session clear failed", error.message)
        assertEquals(true, fixture.lifecycleStore.snapshot().resetInProgress)
        assertEquals(0, fixture.resetRepository.deleteCount)
        assertEquals(77L, fixture.tokenStore.localLastModificationDate())
        assertEquals(0, fixture.authRepository.remoteLogoutAttemptCount)
        assertEquals("access-token", fixture.authRepository.getAccessToken())
        assertFailsWith<SessionResetInProgressException> {
            fixture.service.addBookmark(2, 255)
        }
        fixture.clearAndJoin()
    }

    @Test
    fun `logout blocks mutating writes while token capture is suspended`() = runTest(dispatcher) {
        val fixture = quranDataServiceFixture(
            authRepository = ServiceAuthRepository(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                idToken = "id-token",
                suspendLogoutTokenCapture = true
            )
        )
        advanceUntilIdle()

        val logoutJob = launch {
            fixture.service.logout()
        }

        fixture.authRepository.logoutTokenCaptureStarted.await()
        try {
            assertEquals(AuthState.Idle, fixture.service.authState.value)
            assertEquals(false, fixture.authService.isLoggedIn())
            assertEquals(null, fixture.authService.getAccessToken())
            assertEquals(emptyMap(), fixture.authService.getAuthHeaders())
            assertFailsWith<SessionResetInProgressException> {
                fixture.service.addBookmark(2, 255)
            }
            assertEquals(0, fixture.bookmarksRepository.addCount)
        } finally {
            fixture.authRepository.logoutTokenCaptureCanFinish.complete(Unit)
        }

        logoutJob.join()
        advanceUntilIdle()

        assertEquals(0, fixture.bookmarksRepository.addCount)
        fixture.clearAndJoin()
    }
}

private class QuranDataServiceFixture(
    val authRepository: ServiceAuthRepository = ServiceAuthRepository(),
    val resetRepository: ServiceResetRepository = ServiceResetRepository(),
    val lifecycleStore: SettingsSessionLifecycleStateStore =
        SettingsSessionLifecycleStateStore(MapSettings().toSuspendSettings()),
    useRecordingSyncClient: Boolean = false
) {
    val tokenStore = SyncSettingsLocalModificationDateStore(MapSettings().toSuspendSettings())
    val lifecycleCoordinator = SessionLifecycleCoordinator(lifecycleStore)
    val bookmarksRepository = ServiceBookmarksRepository()
    private val readingBookmarksRepository = ServiceReadingBookmarksRepository()
    val collectionsRepository = ServiceCollectionsRepository()
    private val collectionBookmarksRepository = ServiceCollectionBookmarksRepository()
    private val notesRepository = ServiceNotesRepository()
    val readingSessionsRepository = ServiceReadingSessionsRepository()
    private val importRepository = ServiceImportRepository()
    val syncClient = ServiceSynchronizationClient()
    val authService = AuthService.createWithSessionPublicationGuard(
        authRepository = authRepository,
        sessionPublicationGuard = AuthSessionPublicationGuard {
            !lifecycleStore.snapshot().resetInProgress
        }
    )
    private val pipeline = SyncEnginePipeline(
        bookmarksRepository = bookmarksRepository,
        readingBookmarksRepository = readingBookmarksRepository,
        collectionsRepository = collectionsRepository,
        collectionBookmarksRepository = collectionBookmarksRepository,
        notesRepository = notesRepository,
        readingSessionsRepository = readingSessionsRepository
    )
    val service = createGraph(useRecordingSyncClient).quranDataService

    private fun createGraph(useRecordingSyncClient: Boolean): QuranDataServiceTestGraph {
        val factory =
            if (useRecordingSyncClient) {
                createDynamicGraphFactory<QuranDataServiceTestGraph.Factory>(
                    ServiceSynchronizationClientBindings(syncClient)
                )
            } else {
                createGraphFactory<QuranDataServiceTestGraph.Factory>()
            }
        return factory.create(
            authService = authService,
            pipeline = pipeline,
            environment = SynchronizationEnvironment("https://example.invalid"),
            persistenceResetRepository = resetRepository,
            persistenceImportRepository = importRepository,
            syncLocalModificationDateStore = tokenStore,
            sessionLifecycleCoordinator = lifecycleCoordinator
        )
    }

    suspend fun clearAndJoin() {
        service.clearAndJoin()
        authService.clearAndJoin()
    }

    suspend fun awaitResetInProgress() {
        withContext(Dispatchers.Default) {
            withTimeout(1_000) {
                while (!lifecycleStore.snapshot().resetInProgress) {
                    yield()
                }
            }
        }
    }
}

@DependencyGraph(
    AppScope::class,
    bindingContainers = [QuranDataServiceModule::class],
    excludes = [AuthModule::class, PersistenceModule::class, MobileSyncStorageModule::class]
)
internal interface QuranDataServiceTestGraph {
    val quranDataService: QuranDataService

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides authService: AuthService,
            @Provides pipeline: SyncEnginePipeline,
            @Provides environment: SynchronizationEnvironment,
            @Provides persistenceResetRepository: PersistenceResetRepository,
            @Provides persistenceImportRepository: PersistenceImportRepository,
            @Provides syncLocalModificationDateStore: SyncLocalModificationDateStore,
            @Provides sessionLifecycleCoordinator: SessionLifecycleCoordinator
        ): QuranDataServiceTestGraph
    }
}

@BindingContainer
private class ServiceSynchronizationClientBindings(
    private val syncClient: ServiceSynchronizationClient
) {
    @Provides
    fun provideQuranDataServiceSynchronizationClientFactory(): QuranDataServiceSynchronizationClientFactory =
        QuranDataServiceSynchronizationClientFactory { _, _, _, _, _, _ -> syncClient }
}

private class ServiceAuthRepository(
    private var accessToken: String? = null,
    private var refreshToken: String? = null,
    private var idToken: String? = null,
    private val remoteFailures: List<RemoteLogoutFailure> = emptyList(),
    private val throwOnLogoutTokenCapture: Boolean = false,
    private val throwOnClearLocalSession: Boolean = false,
    private val suspendLogoutTokenCapture: Boolean = false,
    private val blockFirstRefresh: Boolean = false,
    private val blockFirstAuthHeaders: Boolean = false,
    private val remoteLogoutException: Throwable? = null,
    private val onAttemptRemoteLogout: suspend () -> Unit = {}
) : AuthRepository {
    val logoutTokenCaptureStarted = CompletableDeferred<Unit>()
    val logoutTokenCaptureCanFinish = CompletableDeferred<Unit>()
    val firstRefreshStarted = CompletableDeferred<Unit>()
    val firstRefreshCanFinish = CompletableDeferred<Unit>()
    val firstRefreshCancelled = CompletableDeferred<Unit>()
    val firstAuthHeadersStarted = CompletableDeferred<Unit>()
    val firstAuthHeadersCanFinish = CompletableDeferred<Unit>()
    var remoteLogoutAttemptCount = 0
        private set
    var loginCalls = 0
        private set
    var reauthenticationCalls = 0
        private set
    private var authHeadersCalls = 0

    override suspend fun login() {
        loginCalls += 1
        accessToken = "access-token"
    }

    override suspend fun loginWithReauthentication() {
        reauthenticationCalls += 1
        accessToken = "access-token"
    }

    override suspend fun refreshTokensIfNeeded(): Boolean {
        if (blockFirstRefresh) {
            firstRefreshStarted.complete(Unit)
            try {
                firstRefreshCanFinish.await()
            } catch (e: CancellationException) {
                firstRefreshCancelled.complete(Unit)
                throw e
            }
        }
        return accessToken != null
    }

    override suspend fun logout() {
        val failures = attemptRemoteLogout(captureLogoutTokenMaterial())
        if (failures.isNotEmpty()) {
            throw failures.first().exception
        }
        clearLocalSession()
    }

    override suspend fun captureLogoutTokenMaterial(): LogoutTokenMaterial {
        if (suspendLogoutTokenCapture) {
            logoutTokenCaptureStarted.complete(Unit)
            logoutTokenCaptureCanFinish.await()
        }
        if (throwOnLogoutTokenCapture) {
            throw IllegalStateException("token capture failed")
        }
        return LogoutTokenMaterial(refreshToken = refreshToken, idToken = idToken)
    }

    override suspend fun clearLocalSession() {
        if (throwOnClearLocalSession) {
            throw IllegalStateException("local session clear failed")
        }
        accessToken = null
        refreshToken = null
        idToken = null
    }

    override suspend fun attemptRemoteLogout(tokenMaterial: LogoutTokenMaterial): List<RemoteLogoutFailure> {
        remoteLogoutAttemptCount += 1
        onAttemptRemoteLogout()
        remoteLogoutException?.let { throw it }
        return remoteFailures
    }

    override suspend fun getAccessToken(): String? = accessToken

    override suspend fun isLoggedIn(): Boolean = accessToken != null

    override suspend fun getCurrentUser(): UserInfo? = null

    override suspend fun getAuthHeaders(): Map<String, String> {
        authHeadersCalls += 1
        if (blockFirstAuthHeaders && authHeadersCalls == 1) {
            firstAuthHeadersStarted.complete(Unit)
            withContext(NonCancellable) {
                firstAuthHeadersCanFinish.await()
            }
        }
        return accessToken?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()
    }
}

private class ServiceSynchronizationClient : SynchronizationClient {
    var localDataUpdatedCount = 0
        private set
    var applicationStartedCount = 0
        private set
    var triggerSyncImmediatelyCount = 0
        private set
    var cancelSyncingCount = 0
        private set
    var cancelSyncingAndJoinCount = 0
        private set

    override fun localDataUpdated() {
        localDataUpdatedCount += 1
    }

    override fun applicationStarted() {
        applicationStartedCount += 1
    }

    override fun triggerSyncImmediately() {
        triggerSyncImmediatelyCount += 1
    }

    override fun cancelSyncing() {
        cancelSyncingCount += 1
    }

    override suspend fun cancelSyncingAndJoin() {
        cancelSyncingAndJoinCount += 1
    }
}

private class ServiceResetRepository(
    private val failDelete: Boolean = false
) : PersistenceResetRepository {
    var deleteCount = 0

    override fun deleteAllData() {
        if (failDelete) {
            throw IllegalStateException("delete failed")
        }
        deleteCount++
    }
}

private class ServiceImportRepository : PersistenceImportRepository {
    override suspend fun importData(
        data: PersistenceImportData,
        deleteExisting: Boolean
    ): PersistenceImportResult =
        PersistenceImportResult(
            bookmarksImported = 0,
            collectionsImported = 0,
            collectionBookmarksImported = 0,
            readingSessionsImported = 0,
            readingBookmarkImported = false,
            notesImported = 0
        )
}

private class ServiceBookmarksRepository : BookmarksRepository, BookmarksSynchronizationRepository {
    var addCount = 0
    val deleteCalls = mutableListOf<BookmarkDeleteCall>()
    val replaceCalls = mutableListOf<BookmarkCollectionsReplaceCall>()
    val replaceAyahCalls = mutableListOf<BookmarkAyahCollectionsReplaceCall>()
    var deleteResult = true
    var replaceResult = true
    var replaceAyahResultChanged = true
    private val bookmarks = MutableStateFlow<List<AyahBookmark>>(emptyList())

    override suspend fun getAllBookmarks(): List<AyahBookmark> = bookmarks.value
    override fun getBookmarksFlow(): Flow<List<AyahBookmark>> = bookmarks
    override suspend fun addBookmark(sura: Int, ayah: Int): AyahBookmark =
        AyahBookmark(sura, ayah, testTimestamp(), "bookmark-${++addCount}")

    override suspend fun addBookmark(sura: Int, ayah: Int, timestamp: com.quran.shared.persistence.util.PlatformDateTime): AyahBookmark =
        addBookmark(sura, ayah)

    override suspend fun addBookmark(sura: Int, ayah: Int, collectionLocalIds: List<String>?): AyahBookmark =
        addBookmark(sura, ayah)

    override suspend fun addBookmark(
        sura: Int,
        ayah: Int,
        collectionLocalIds: List<String>?,
        timestamp: com.quran.shared.persistence.util.PlatformDateTime
    ): AyahBookmark = addBookmark(sura, ayah)

    override suspend fun replaceBookmarkCollections(
        localId: String,
        collectionLocalIds: List<String>?
    ): Boolean {
        replaceCalls += BookmarkCollectionsReplaceCall(localId, collectionLocalIds)
        return replaceResult
    }

    override suspend fun replaceBookmarkCollections(
        localId: String,
        collectionLocalIds: List<String>?,
        timestamp: com.quran.shared.persistence.util.PlatformDateTime
    ): Boolean {
        replaceCalls += BookmarkCollectionsReplaceCall(localId, collectionLocalIds, timestamp)
        return replaceResult
    }

    override suspend fun replaceAyahBookmarkCollections(
        sura: Int,
        ayah: Int,
        collectionLocalIds: List<String>?
    ): BookmarkCollectionsReplacementResult {
        replaceAyahCalls += BookmarkAyahCollectionsReplaceCall(sura, ayah, collectionLocalIds)
        return BookmarkCollectionsReplacementResult(
            bookmark = AyahBookmark(sura, ayah, testTimestamp(), "bookmark-replaced"),
            changed = replaceAyahResultChanged
        )
    }

    override suspend fun replaceAyahBookmarkCollections(
        sura: Int,
        ayah: Int,
        collectionLocalIds: List<String>?,
        timestamp: com.quran.shared.persistence.util.PlatformDateTime
    ): BookmarkCollectionsReplacementResult {
        replaceAyahCalls += BookmarkAyahCollectionsReplaceCall(sura, ayah, collectionLocalIds, timestamp)
        return BookmarkCollectionsReplacementResult(
            bookmark = AyahBookmark(sura, ayah, timestamp, "bookmark-replaced"),
            changed = replaceAyahResultChanged
        )
    }

    override suspend fun deleteBookmark(sura: Int, ayah: Int): Boolean {
        deleteCalls += BookmarkAyahDeleteCall(sura, ayah)
        return deleteResult
    }
    override suspend fun deleteBookmark(bookmark: AyahBookmark): Boolean {
        deleteCalls += BookmarkModelDeleteCall(bookmark)
        return deleteResult
    }
    override suspend fun deleteBookmark(localId: String): Boolean {
        deleteCalls += BookmarkLocalIdDeleteCall(localId)
        return deleteResult
    }
    override suspend fun fetchMutatedBookmarks(): List<LocalModelMutation<RemoteBookmark>> = emptyList()
    override suspend fun markMutatedBookmarksInFlight(acks: List<LocalMutationAck>): List<LocalMutationAck> = emptyList()
    override suspend fun rollbackMutatedBookmarksInFlight(acks: List<LocalMutationAck>) = Unit
    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteBookmark>>,
        localMutationsToClear: List<LocalModelMutation<RemoteBookmark>>,
        writeBoundaryGuard: PersistenceWriteBoundaryGuard
    ) = writeBoundaryGuard.checkWriteBoundary()
    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> =
        remoteIDs.associateWith { false }
    override suspend fun fetchBookmarkByRemoteId(remoteId: String): RemoteBookmark? = null
}

private data class BookmarkCollectionsReplaceCall(
    val localId: String,
    val collectionLocalIds: List<String>?,
    val timestamp: PlatformDateTime? = null
)

private data class BookmarkAyahCollectionsReplaceCall(
    val sura: Int,
    val ayah: Int,
    val collectionLocalIds: List<String>?,
    val timestamp: PlatformDateTime? = null
)

private sealed interface BookmarkDeleteCall

private data class BookmarkAyahDeleteCall(
    val sura: Int,
    val ayah: Int
) : BookmarkDeleteCall

private data class BookmarkLocalIdDeleteCall(
    val localId: String
) : BookmarkDeleteCall

private data class BookmarkModelDeleteCall(
    val bookmark: AyahBookmark
) : BookmarkDeleteCall

private class ServiceReadingBookmarksRepository : ReadingBookmarksRepository {
    override suspend fun getReadingBookmark(): ReadingBookmark? = null
    override fun getReadingBookmarkFlow(): Flow<ReadingBookmark?> = MutableStateFlow(null)
    override suspend fun addAyahReadingBookmark(sura: Int, ayah: Int): AyahReadingBookmark =
        AyahReadingBookmark(sura, ayah, testTimestamp(), "reading-ayah")
    override suspend fun addAyahReadingBookmark(
        sura: Int,
        ayah: Int,
        timestamp: com.quran.shared.persistence.util.PlatformDateTime
    ): AyahReadingBookmark = addAyahReadingBookmark(sura, ayah)
    override suspend fun addPageReadingBookmark(page: Int): PageReadingBookmark =
        PageReadingBookmark(page, testTimestamp(), "reading-page")
    override suspend fun addPageReadingBookmark(
        page: Int,
        timestamp: com.quran.shared.persistence.util.PlatformDateTime
    ): PageReadingBookmark = addPageReadingBookmark(page)
    override suspend fun deleteReadingBookmark(): Boolean = true
}

private data class CollectionUpdateCall(
    val localId: String,
    val name: String,
    val timestamp: PlatformDateTime?
)

private data class CollectionAddCall(
    val name: String,
    val timestamp: PlatformDateTime?
)

private data class CollectionDeleteCall(
    val localId: String
)

private class ServiceCollectionsRepository : CollectionsRepository, CollectionsSynchronizationRepository {
    val addCalls = mutableListOf<CollectionAddCall>()
    val updateCalls = mutableListOf<CollectionUpdateCall>()
    val deleteCalls = mutableListOf<CollectionDeleteCall>()
    var deleteResult = true

    override suspend fun getAllCollections(): List<Collection> = emptyList()
    override suspend fun addCollection(name: String): Collection {
        addCalls += CollectionAddCall(name, null)
        return Collection(name, testTimestamp(), "collection-${addCalls.size}")
    }
    override suspend fun addCollection(
        name: String,
        timestamp: com.quran.shared.persistence.util.PlatformDateTime
    ): Collection {
        addCalls += CollectionAddCall(name, timestamp)
        return Collection(name, timestamp, "collection-${addCalls.size}")
    }
    override suspend fun updateCollection(localId: String, name: String): Collection {
        updateCalls += CollectionUpdateCall(localId, name, null)
        return Collection(name, testTimestamp(), localId)
    }
    override suspend fun updateCollection(
        localId: String,
        name: String,
        timestamp: com.quran.shared.persistence.util.PlatformDateTime
    ): Collection {
        updateCalls += CollectionUpdateCall(localId, name, timestamp)
        return Collection(name, timestamp, localId)
    }
    override suspend fun deleteCollection(localId: String): Boolean {
        deleteCalls += CollectionDeleteCall(localId)
        return deleteResult
    }
    override fun getCollectionsFlow(): Flow<List<Collection>> = MutableStateFlow(emptyList())
    override suspend fun fetchMutatedCollections(): List<LocalModelMutation<LocalSyncCollection>> = emptyList()
    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteCollection>>,
        localMutationsToClear: List<LocalModelMutation<LocalSyncCollection>>,
        writeBoundaryGuard: PersistenceWriteBoundaryGuard
    ) = writeBoundaryGuard.checkWriteBoundary()
    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> =
        remoteIDs.associateWith { false }
}

private class ServiceCollectionBookmarksRepository :
    CollectionBookmarksRepository,
    CollectionBookmarksSynchronizationRepository {
    override suspend fun getBookmarksForCollection(collectionLocalId: String): List<CollectionAyahBookmark> = emptyList()
    override suspend fun addBookmarkToCollection(
        collectionLocalId: String,
        bookmark: AyahBookmark
    ): CollectionAyahBookmark = bookmarkLink(collectionLocalId)
    override suspend fun addBookmarkToCollection(
        collectionLocalId: String,
        bookmark: AyahBookmark,
        timestamp: com.quran.shared.persistence.util.PlatformDateTime
    ): CollectionAyahBookmark = bookmarkLink(collectionLocalId)
    override suspend fun addAyahBookmarkToCollection(
        collectionLocalId: String,
        sura: Int,
        ayah: Int
    ): CollectionAyahBookmark = bookmarkLink(collectionLocalId)
    override suspend fun addAyahBookmarkToCollection(
        collectionLocalId: String,
        sura: Int,
        ayah: Int,
        timestamp: com.quran.shared.persistence.util.PlatformDateTime
    ): CollectionAyahBookmark = bookmarkLink(collectionLocalId)
    override suspend fun removeBookmarkFromCollection(collectionLocalId: String, bookmark: AyahBookmark): Boolean = true
    override suspend fun removeAyahBookmarkFromCollection(collectionAyahBookmark: CollectionAyahBookmark): Boolean = true
    override fun getBookmarksForCollectionFlow(collectionLocalId: String): Flow<List<CollectionAyahBookmark>> =
        MutableStateFlow(emptyList())
    override suspend fun fetchMutatedCollectionBookmarks(): List<LocalModelMutation<LocalSyncCollectionAyahBookmark>> =
        emptyList()
    override suspend fun markMutatedCollectionBookmarksInFlight(acks: List<LocalMutationAck>): List<LocalMutationAck> =
        emptyList()
    override suspend fun rollbackMutatedCollectionBookmarksInFlight(acks: List<LocalMutationAck>) = Unit
    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteCollectionBookmark>>,
        localMutationsToClear: List<LocalModelMutation<LocalSyncCollectionAyahBookmark>>,
        writeBoundaryGuard: PersistenceWriteBoundaryGuard
    ) = writeBoundaryGuard.checkWriteBoundary()
    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> =
        remoteIDs.associateWith { false }
    override suspend fun fetchCollectionBookmarkByRemoteId(remoteId: String): CollectionAyahBookmark? = null
}

private class ServiceNotesRepository : NotesRepository, NotesSynchronizationRepository {
    override suspend fun getAllNotes(): List<Note> = emptyList()
    override suspend fun addNote(body: String, startSura: Int, startAyah: Int, endSura: Int, endAyah: Int): Note =
        Note(body, startSura, startAyah, endSura, endAyah, testTimestamp(), "note")
    override suspend fun addNote(
        body: String,
        startSura: Int,
        startAyah: Int,
        endSura: Int,
        endAyah: Int,
        timestamp: com.quran.shared.persistence.util.PlatformDateTime
    ): Note = addNote(body, startSura, startAyah, endSura, endAyah)
    override suspend fun updateNote(
        localId: String,
        body: String,
        startSura: Int,
        startAyah: Int,
        endSura: Int,
        endAyah: Int
    ): Note = addNote(body, startSura, startAyah, endSura, endAyah)
    override suspend fun updateNote(
        localId: String,
        body: String,
        startSura: Int,
        startAyah: Int,
        endSura: Int,
        endAyah: Int,
        timestamp: com.quran.shared.persistence.util.PlatformDateTime
    ): Note = addNote(body, startSura, startAyah, endSura, endAyah)
    override suspend fun deleteNote(localId: String): Boolean = true
    override fun getNotesFlow(): Flow<List<Note>> = MutableStateFlow(emptyList())
    override suspend fun fetchMutatedNotes(lastModified: Long): List<LocalModelMutation<LocalSyncNote>> = emptyList()
    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteNote>>,
        localMutationsToClear: List<LocalModelMutation<LocalSyncNote>>,
        writeBoundaryGuard: PersistenceWriteBoundaryGuard
    ) = writeBoundaryGuard.checkWriteBoundary()
    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> =
        remoteIDs.associateWith { false }
}

private data class ReadingSessionDeleteCall(
    val sura: Int,
    val ayah: Int
)

private class ServiceReadingSessionsRepository : ReadingSessionsRepository, ReadingSessionsSynchronizationRepository {
    val deleteCalls = mutableListOf<ReadingSessionDeleteCall>()
    var deleteResult = true

    override suspend fun getReadingSessions(): List<ReadingSession> = emptyList()
    override suspend fun addReadingSession(sura: Int, ayah: Int): ReadingSession =
        ReadingSession(sura, ayah, testTimestamp(), "reading-session")
    override suspend fun addReadingSession(
        sura: Int,
        ayah: Int,
        timestamp: com.quran.shared.persistence.util.PlatformDateTime
    ): ReadingSession = addReadingSession(sura, ayah)
    override suspend fun updateReadingSession(localId: String, sura: Int, ayah: Int): ReadingSession =
        addReadingSession(sura, ayah)
    override suspend fun updateReadingSession(
        localId: String,
        sura: Int,
        ayah: Int,
        timestamp: com.quran.shared.persistence.util.PlatformDateTime
    ): ReadingSession = addReadingSession(sura, ayah)
    override fun getReadingSessionsFlow(): Flow<List<ReadingSession>> = MutableStateFlow(emptyList())
    override suspend fun deleteReadingSession(sura: Int, ayah: Int): Boolean {
        deleteCalls += ReadingSessionDeleteCall(sura, ayah)
        return deleteResult
    }
    override suspend fun fetchMutatedReadingSessions(): List<LocalModelMutation<LocalSyncReadingSession>> = emptyList()
    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteReadingSession>>,
        localMutationIdsToClear: List<String>,
        writeBoundaryGuard: PersistenceWriteBoundaryGuard
    ) = writeBoundaryGuard.checkWriteBoundary()
    override suspend fun applyRemoteChangesForMutations(
        updatesToPersist: List<RemoteModelMutation<RemoteReadingSession>>,
        localMutationsToClear: List<LocalModelMutation<LocalSyncReadingSession>>,
        writeBoundaryGuard: PersistenceWriteBoundaryGuard
    ) = writeBoundaryGuard.checkWriteBoundary()
    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> =
        remoteIDs.associateWith { false }
    override suspend fun fetchReadingSessionByRemoteId(remoteId: String): ReadingSession? = null
}

private fun bookmarkLink(collectionLocalId: String): CollectionAyahBookmark =
    CollectionAyahBookmark(
        collectionLocalId = collectionLocalId,
        collectionRemoteId = null,
        bookmarkLocalId = "bookmark",
        bookmarkRemoteId = null,
        sura = 2,
        ayah = 255,
        lastUpdated = testTimestamp(),
        localId = "collection-bookmark"
    )

private fun testTimestamp(): com.quran.shared.persistence.util.PlatformDateTime =
    Instant.fromEpochMilliseconds(1).toPlatform()
