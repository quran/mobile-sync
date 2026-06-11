package com.quran.shared.auth.service

import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.AuthRepositoryLoginCommitCallbacks
import com.quran.shared.auth.repository.LogoutTokenCaptureException
import com.quran.shared.auth.repository.LogoutTokenMaterial
import com.quran.shared.auth.repository.RemoteLogoutFailure
import com.quran.shared.auth.repository.RemoteLogoutOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AuthServiceTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `stored token keeps session authenticated when profile is missing`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = "access-token",
            currentUser = null
        )
        val service = AuthService(repository)

        advanceUntilIdle()

        val state = assertIs<AuthState.Success>(service.authState.value)
        assertNull(state.userInfo)
        assertTrue(service.isLoggedIn())
        assertEquals("access-token", service.getAccessToken())
        assertEquals(
            mapOf("Authorization" to "Bearer access-token"),
            service.getAuthHeaders()
        )
    }

    @Test
    fun `auth headers lazily publish valid stored session before startup check runs`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = "access-token",
            currentUser = null
        )
        val service = AuthService(repository)

        assertEquals(
            mapOf("Authorization" to "Bearer access-token"),
            service.getAuthHeaders()
        )
        assertTrue(service.isLoggedIn())
        assertEquals("access-token", service.getAccessToken())
        assertIs<AuthState.Success>(service.authState.value)
    }

    @Test
    fun `startup validation keeps expired stored session logged out`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = "expired-access-token",
            currentUser = null,
            refreshResults = listOf(false)
        )
        val service = AuthService(repository)

        advanceUntilIdle()

        assertEquals(1, repository.refreshCalls)
        assertFalse(service.isLoggedIn())
        assertNull(service.getAccessToken())
        assertIs<AuthState.Idle>(service.authState.value)
    }

    @Test
    fun `fresh explicit login after invalid stored session succeeds`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = "expired-access-token",
            loginAccessToken = "new-access-token",
            currentUser = null,
            refreshResults = listOf(false, true)
        )
        val service = AuthService(repository)

        advanceUntilIdle()
        assertIs<AuthState.Idle>(service.authState.value)

        service.login()

        assertEquals("new-access-token", service.getAccessToken())
        assertIs<AuthState.Success>(service.authState.value)
        assertEquals(2, repository.refreshCalls)
    }

    @Test
    fun `startup publication guard suppresses stored session`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = "stale-access-token",
            currentUser = null
        )
        val service = AuthService.createWithSessionPublicationGuard(
            authRepository = repository,
            sessionPublicationGuard = AuthSessionPublicationGuard { false }
        )

        advanceUntilIdle()

        assertFalse(service.isLoggedIn())
        assertNull(service.getAccessToken())
        assertIs<AuthState.Idle>(service.authState.value)
        assertEquals("stale-access-token", repository.getAccessToken())
    }

    @Test
    fun `refresh does not publish stored session when publication guard suppresses it`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = "stale-access-token",
                currentUser = null,
                refreshResult = true
            )
            val service = AuthService.createWithSessionPublicationGuard(
                authRepository = repository,
                sessionPublicationGuard = AuthSessionPublicationGuard { false }
            )

            advanceUntilIdle()

            assertFalse(service.refreshAccessTokenIfNeeded())

            assertEquals(0, repository.refreshCalls)
            assertFalse(service.isLoggedIn())
            assertNull(service.getAccessToken())
            assertIs<AuthState.Idle>(service.authState.value)
            assertEquals("stale-access-token", repository.getAccessToken())
        }

    @Test
    fun `publication uses latest token when repository rotates token during read`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = "old-access-token",
                currentUser = null,
                accessTokenAfterCurrentUserRead = "rotated-access-token"
            )
            val service = AuthService(repository)

            advanceUntilIdle()

            val state = assertIs<AuthState.Success>(service.authState.value)
            assertNull(state.userInfo)
            assertTrue(service.isLoggedIn())
            assertEquals("rotated-access-token", service.getAccessToken())
            assertEquals(
                mapOf("Authorization" to "Bearer rotated-access-token"),
                service.getAuthHeaders()
            )
        }

    @Test
    fun `login succeeds when profile fetch leaves user info missing`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = null,
            loginAccessToken = "login-access-token",
            currentUser = null
        )
        val service = AuthService(repository)
        advanceUntilIdle()

        service.login()

        val state = assertIs<AuthState.Success>(service.authState.value)
        assertNull(state.userInfo)
        assertTrue(service.isLoggedIn())
        assertEquals("login-access-token", service.getAccessToken())
    }

    @Test
    fun `logout clears published token when remote logout fails after local session clear`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = "access-token",
            currentUser = null,
            throwAfterLocalLogoutClear = true
        )
        val service = AuthService(repository)
        advanceUntilIdle()

        service.logout()

        assertNull(repository.getAccessToken())
        assertNull(service.getAccessToken())
        assertIs<AuthState.Idle>(service.authState.value)
    }

    @Test
    fun `logout clears published session before slow remote cleanup completes`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = "access-token",
            currentUser = null,
            suspendRemoteLogout = true
        )
        val service = AuthService(repository)
        advanceUntilIdle()

        val logoutJob = launch {
            service.logout()
        }
        runCurrent()

        assertTrue(repository.remoteLogoutStarted.isCompleted)
        assertNull(repository.getAccessToken())
        assertNull(service.getAccessToken())
        assertIs<AuthState.Idle>(service.authState.value)

        repository.remoteLogoutCanFinish.complete(Unit)
        logoutJob.join()
    }

    @Test
    fun `logout remote cleanup cancellation propagates`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = "access-token",
            currentUser = null,
            remoteLogoutException = CancellationException("remote logout cancelled")
        )
        val service = AuthService(repository)
        advanceUntilIdle()

        assertFailsWith<CancellationException> {
            service.logout()
        }

        assertNull(repository.getAccessToken())
        assertNull(service.getAccessToken())
        assertIs<AuthState.Idle>(service.authState.value)
    }

    @Test
    fun `logout clears local session when token material capture fails`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = "access-token",
            currentUser = null,
            throwOnLogoutTokenCapture = true
        )
        val service = AuthService(repository)
        advanceUntilIdle()

        service.logout()

        assertNull(repository.getAccessToken())
        assertNull(service.getAccessToken())
        assertIs<AuthState.Idle>(service.authState.value)
        assertEquals(false, repository.remoteLogoutStarted.isCompleted)
    }

    @Test
    fun `logout token capture warning does not clear newer persisted session`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = "old-access-token",
            loginAccessToken = "new-access-token",
            currentUser = null,
            throwOnLogoutTokenCapture = true,
            replaceSessionBeforeLogoutTokenCaptureFailure = true
        )
        val service = AuthService(repository)
        advanceUntilIdle()

        service.logout()

        assertEquals("new-access-token", repository.getAccessToken())
        assertEquals(1, repository.clearLocalSessionCalls)
        assertEquals(false, repository.remoteLogoutStarted.isCompleted)
    }

    @Test
    fun `logout clears published session and auth headers before slow token capture completes`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = "access-token",
            currentUser = null,
            suspendLogoutTokenCapture = true
        )
        val service = AuthService(repository)
        advanceUntilIdle()

        val logoutJob = launch {
            service.logout()
        }
        runCurrent()

        assertTrue(repository.logoutTokenCaptureStarted.isCompleted)
        assertNull(repository.getAccessToken())
        assertNull(service.getAccessToken())
        assertIs<AuthState.Idle>(service.authState.value)
        assertEquals(emptyMap(), service.getAuthHeaders())

        repository.logoutTokenCaptureCanFinish.complete(Unit)
        logoutJob.join()
        advanceUntilIdle()

        assertNull(repository.getAccessToken())
        assertNull(service.getAccessToken())
        assertIs<AuthState.Idle>(service.authState.value)
    }

    @Test
    fun `local clear cancels and drains active service login before clearing repository session`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = null,
                loginAccessToken = "stale-login-access-token",
                currentUser = null,
                suspendFirstLogin = true,
                ignoreFirstLoginCancellation = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()

            val login = async {
                runCatching { service.login() }
            }
            runCurrent()
            assertTrue(repository.firstLoginStarted.isCompleted)

            val clear = launch {
                service.clearLocalSession()
            }
            runCurrent()

            assertTrue(repository.firstLoginCancellationObserved.isCompleted)
            assertFalse(clear.isCompleted)

            repository.firstLoginCanFinish.complete(Unit)
            clear.join()

            assertTrue(login.await().isFailure)
            assertNull(repository.getAccessToken())
            assertNull(service.getAccessToken())
            assertEquals(emptyMap(), service.getAuthHeaders())
            assertIs<AuthState.Idle>(service.authState.value)
        }

    @Test
    fun `local clear failure settles clearing and allows later login`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = "old-access-token",
                loginAccessToken = "new-access-token",
                currentUser = null,
                throwOnClearLocalSession = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()

            val clear = runCatching {
                service.clearLocalSession()
            }

            assertTrue(clear.isFailure)
            assertNull(service.getAccessToken())
            assertIs<AuthState.Idle>(service.authState.value)

            service.login()

            assertEquals("new-access-token", service.getAccessToken())
            assertIs<AuthState.Success>(service.authState.value)
        }

    @Test
    fun `capture clear failure settles clearing and allows later login`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = "old-access-token",
                loginAccessToken = "new-access-token",
                currentUser = null,
                throwOnLogoutTokenCapture = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()

            val capture = runCatching {
                service.captureLogoutTokenMaterialForLogout()
            }

            assertTrue(capture.isFailure)
            assertNull(service.getAccessToken())
            assertIs<AuthState.Idle>(service.authState.value)

            service.login()

            assertEquals("new-access-token", service.getAccessToken())
            assertIs<AuthState.Success>(service.authState.value)
        }

    @Test
    fun `auth headers stay empty while local clear drains committed active login`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = null,
                loginAccessToken = "stale-login-access-token",
                currentUser = null,
                suspendFirstLoginAfterTokenWrite = true,
                ignoreFirstLoginCancellation = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()

            val login = async {
                runCatching { service.login() }
            }
            runCurrent()
            assertTrue(repository.firstLoginAfterTokenWriteStarted.isCompleted)
            assertEquals("stale-login-access-token", repository.getAccessToken())

            val clear = launch {
                service.clearLocalSession()
            }
            runCurrent()
            assertTrue(repository.firstLoginCancellationObserved.isCompleted)
            assertFalse(clear.isCompleted)

            assertEquals(emptyMap(), service.getAuthHeaders())
            assertNull(service.getAccessToken())
            assertIs<AuthState.Idle>(service.authState.value)

            repository.firstLoginAfterTokenWriteCanFinish.complete(Unit)
            clear.join()

            assertTrue(login.await().isFailure)
            assertNull(repository.getAccessToken())
            assertEquals(emptyMap(), service.getAuthHeaders())
            assertIs<AuthState.Idle>(service.authState.value)
        }

    @Test
    fun `cancelled logout still clears local repository session after unpublishing auth`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = "access-token",
                currentUser = null,
                suspendBeforeLocalLogoutClear = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()

            val logoutJob = launch {
                service.logout()
            }
            runCurrent()

            assertTrue(repository.logoutTokenCaptureStarted.isCompleted)
            assertNull(service.getAccessToken())
            assertIs<AuthState.Idle>(service.authState.value)

            logoutJob.cancel()
            runCurrent()

            repository.logoutTokenCaptureCanFinish.complete(Unit)
            logoutJob.cancelAndJoin()

            assertNull(repository.getAccessToken())
            assertEquals(1, repository.clearLocalSessionCalls)
        }

    @Test
    fun `in-flight session check does not restore success after logout`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = "access-token",
            currentUser = null,
            suspendCurrentUser = true
        )
        val service = AuthService(repository)

        runCurrent()
        assertTrue(repository.currentUserStarted.isCompleted)

        service.logout()
        assertNull(repository.getAccessToken())
        assertNull(service.getAccessToken())
        assertIs<AuthState.Idle>(service.authState.value)

        repository.currentUserCanFinish.complete(Unit)
        advanceUntilIdle()

        assertNull(service.getAccessToken())
        assertIs<AuthState.Idle>(service.authState.value)
    }

    @Test
    fun `logout hides cached auth while current session publication storage read is blocked`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = "access-token",
                currentUser = null,
                refreshResult = true,
                suspendSecondIsLoggedInRead = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()
            assertEquals("access-token", service.getAccessToken())

            val refresh = async {
                service.refreshAccessTokenIfNeeded()
            }
            runCurrent()
            assertTrue(repository.secondIsLoggedInReadStarted.isCompleted)

            val logout = launch {
                service.logout()
            }
            runCurrent()

            assertNull(repository.getAccessToken())
            assertNull(service.getAccessToken())
            assertIs<AuthState.Idle>(service.authState.value)

            repository.secondIsLoggedInReadCanFinish.complete(Unit)
            logout.join()
            assertTrue(refresh.await())
            advanceUntilIdle()

            assertNull(service.getAccessToken())
            assertIs<AuthState.Idle>(service.authState.value)
        }

    @Test
    fun `stale startup session check does not clear explicit login session without logout`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = "old-access-token",
            loginAccessToken = "new-access-token",
            currentUser = null,
            refreshResults = listOf(false, true),
            suspendRefresh = true
        )
        val service = AuthService(repository)

        runCurrent()
        assertTrue(repository.refreshStarted.isCompleted)

        val login = async {
            service.login()
        }
        runCurrent()
        assertFalse(login.isCompleted)

        repository.refreshCanFinish.complete(Unit)
        login.await()
        advanceUntilIdle()

        assertEquals("new-access-token", service.getAccessToken())
        assertIs<AuthState.Success>(service.authState.value)
    }

    @Test
    fun `startup validation during active login does not clear or fail login`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = null,
                loginAccessToken = "login-access-token",
                currentUser = null,
                refreshResults = listOf(false, true),
                suspendRefreshOnCalls = setOf(1),
                suspendFirstLogin = true
            )
            val service = AuthService(repository)

            runCurrent()
            assertTrue(repository.refreshStarted.isCompleted)

            val login = async {
                service.login()
            }
            runCurrent()
            assertTrue(repository.firstLoginStarted.isCompleted)
            assertIs<AuthState.Loading>(service.authState.value)

            repository.refreshCanFinish.complete(Unit)
            runCurrent()

            assertIs<AuthState.Loading>(service.authState.value)
            assertNull(service.getAccessToken())

            repository.firstLoginCanFinish.complete(Unit)
            login.await()
            advanceUntilIdle()

            assertEquals("login-access-token", service.getAccessToken())
            assertIs<AuthState.Success>(service.authState.value)
        }

    @Test
    fun `in-flight login publication does not restore success after logout`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = null,
            loginAccessToken = "login-access-token",
            currentUser = null,
            suspendCurrentUser = true
        )
        val service = AuthService(repository)
        advanceUntilIdle()

        val loginResult = async {
            runCatching { service.login() }
        }
        runCurrent()
        assertTrue(repository.currentUserStarted.isCompleted)

        service.logout()
        assertNull(repository.getAccessToken())
        assertNull(service.getAccessToken())
        assertIs<AuthState.Idle>(service.authState.value)

        repository.currentUserCanFinish.complete(Unit)
        assertTrue(loginResult.await().isFailure)
        advanceUntilIdle()

        assertNull(service.getAccessToken())
        assertIs<AuthState.Idle>(service.authState.value)
    }

    @Test
    fun `login after logout does not await stale active login`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = null,
            loginAccessToken = "new-access-token",
            currentUser = null,
            suspendFirstLoginAndFail = true
        )
        val service = AuthService(repository)
        advanceUntilIdle()

        val staleLogin = async {
            runCatching { service.login() }
        }
        runCurrent()
        assertTrue(repository.firstLoginStarted.isCompleted)

        service.logout()
        service.login()

        assertEquals("new-access-token", service.getAccessToken())
        assertIs<AuthState.Success>(service.authState.value)

        repository.firstLoginCanFail.complete(Unit)

        assertTrue(staleLogin.await().isFailure)
        advanceUntilIdle()
        assertEquals("new-access-token", service.getAccessToken())
        assertIs<AuthState.Success>(service.authState.value)
    }

    @Test
    fun `startup without stored tokens remains idle`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = null,
            currentUser = null
        )
        val service = AuthService(repository)

        advanceUntilIdle()

        assertNull(service.getAccessToken())
        assertIs<AuthState.Idle>(service.authState.value)
    }

    @Test
    fun `startup without stored tokens ignores abandoned login recovery`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = null,
            currentUser = null
        )
        val service = AuthService(repository)

        advanceUntilIdle()

        assertNull(service.getAccessToken())
        assertIs<AuthState.Idle>(service.authState.value)
    }

    @Test
    fun `logout does not wait for abandoned startup login recovery`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = null,
            currentUser = null
        )
        val service = AuthService(repository)

        advanceUntilIdle()
        service.logout()

        assertNull(repository.getAccessToken())
        assertNull(service.getAccessToken())
        assertIs<AuthState.Idle>(service.authState.value)
    }

    @Test
    fun `fresh login after abandoned startup state succeeds`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = null,
            loginAccessToken = "new-access-token",
            currentUser = null
        )
        val service = AuthService(repository)

        advanceUntilIdle()
        assertIs<AuthState.Idle>(service.authState.value)

        service.login()

        assertEquals("new-access-token", service.getAccessToken())
        assertIs<AuthState.Success>(service.authState.value)
    }

    @Test
    fun `abandoned startup login failure does not overwrite logout state`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = null,
            currentUser = null
        )
        val service = AuthService(repository)

        advanceUntilIdle()
        service.logout()

        assertNull(service.getAccessToken())
        assertIs<AuthState.Idle>(service.authState.value)
    }

    @Test
    fun `abandoned startup login failure does not overwrite newer login success`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = null,
            loginAccessToken = "new-access-token",
            currentUser = null
        )
        val service = AuthService(repository)

        advanceUntilIdle()

        service.login()

        assertEquals("new-access-token", service.getAccessToken())
        assertIs<AuthState.Success>(service.authState.value)
    }

    @Test
    fun `stale refresh false does not clear newer login session`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = "old-access-token",
            loginAccessToken = "new-access-token",
            currentUser = null,
            refreshResults = listOf(true, false, true),
            suspendRefreshOnCalls = setOf(2)
        )
        val service = AuthService(repository)
        advanceUntilIdle()

        val refresh = async {
            service.refreshAccessTokenIfNeeded()
        }
        runCurrent()
        assertTrue(repository.refreshStarted.isCompleted)

        service.logout()
        service.login()
        assertEquals("new-access-token", service.getAccessToken())
        assertIs<AuthState.Success>(service.authState.value)

        repository.refreshCanFinish.complete(Unit)

        assertFalse(refresh.await())
        advanceUntilIdle()
        assertEquals("new-access-token", service.getAccessToken())
        assertIs<AuthState.Success>(service.authState.value)
    }

    @Test
    fun `stale refresh false does not clear explicit login session without logout`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = "old-access-token",
            loginAccessToken = "new-access-token",
            currentUser = null,
            refreshResults = listOf(true, false, true),
            suspendRefreshOnCalls = setOf(2)
        )
        val service = AuthService(repository)
        advanceUntilIdle()

        val refresh = async {
            service.refreshAccessTokenIfNeeded()
        }
        runCurrent()
        assertTrue(repository.refreshStarted.isCompleted)

        service.login()
        assertEquals("new-access-token", service.getAccessToken())
        assertIs<AuthState.Success>(service.authState.value)

        repository.refreshCanFinish.complete(Unit)

        assertFalse(refresh.await())
        advanceUntilIdle()
        assertEquals("new-access-token", service.getAccessToken())
        assertIs<AuthState.Success>(service.authState.value)
    }

    @Test
    fun `stale empty auth headers do not clear newer login session`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = "old-access-token",
            loginAccessToken = "new-access-token",
            currentUser = null,
            forcedAuthHeaders = emptyMap(),
            suspendAuthHeaders = true
        )
        val service = AuthService(repository)
        advanceUntilIdle()

        val headers = async {
            service.getAuthHeaders()
        }
        runCurrent()
        assertTrue(repository.authHeadersStarted.isCompleted)

        service.logout()
        service.login()
        assertEquals("new-access-token", service.getAccessToken())
        assertIs<AuthState.Success>(service.authState.value)

        repository.authHeadersCanFinish.complete(Unit)

        assertEquals(emptyMap(), headers.await())
        advanceUntilIdle()
        assertEquals("new-access-token", service.getAccessToken())
        assertIs<AuthState.Success>(service.authState.value)
    }

    @Test
    fun `stale nonempty auth headers return empty after newer login session`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = "old-access-token",
            loginAccessToken = "new-access-token",
            currentUser = null,
            forcedAuthHeaders = mapOf("Authorization" to "Bearer old-access-token"),
            suspendAuthHeaders = true
        )
        val service = AuthService(repository)
        advanceUntilIdle()

        val headers = async {
            service.getAuthHeaders()
        }
        runCurrent()
        assertTrue(repository.authHeadersStarted.isCompleted)

        service.logout()
        service.login()
        assertEquals("new-access-token", service.getAccessToken())
        assertIs<AuthState.Success>(service.authState.value)

        repository.authHeadersCanFinish.complete(Unit)

        assertEquals(emptyMap(), headers.await())
        advanceUntilIdle()
        assertEquals("new-access-token", service.getAccessToken())
        assertIs<AuthState.Success>(service.authState.value)
    }

    @Test
    fun `stale nonempty auth headers return empty when logout starts during cache refresh`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = "old-access-token",
            currentUser = null,
            forcedAuthHeaders = mapOf("Authorization" to "Bearer old-access-token"),
            suspendThirdAccessTokenRead = true
        )
        val service = AuthService(repository)
        advanceUntilIdle()

        val headers = async {
            service.getAuthHeaders()
        }
        runCurrent()
        assertTrue(repository.thirdAccessTokenReadStarted.isCompleted)

        service.logout()
        assertNull(service.getAccessToken())
        assertIs<AuthState.Idle>(service.authState.value)

        repository.thirdAccessTokenReadCanFinish.complete(Unit)

        assertEquals(emptyMap(), headers.await())
        advanceUntilIdle()
        assertNull(service.getAccessToken())
        assertIs<AuthState.Idle>(service.authState.value)
    }

    @Test
    fun `auth header refresh failure reconciles published auth with repository state`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = "old-access-token",
                currentUser = null,
                authHeadersFailure = IllegalStateException("header refresh commit failed"),
                clearTokenBeforeAuthHeadersFailure = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()
            assertEquals("old-access-token", service.getAccessToken())

            val result = runCatching {
                service.getAuthHeaders()
            }

            assertTrue(result.isFailure)
            assertNull(service.getAccessToken())
            assertIs<AuthState.Idle>(service.authState.value)
        }

    @Test
    fun `stale empty auth headers do not clear explicit login session without logout`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = "old-access-token",
            loginAccessToken = "new-access-token",
            currentUser = null,
            forcedAuthHeaders = emptyMap(),
            suspendAuthHeaders = true
        )
        val service = AuthService(repository)
        advanceUntilIdle()

        val headers = async {
            service.getAuthHeaders()
        }
        runCurrent()
        assertTrue(repository.authHeadersStarted.isCompleted)

        service.login()
        assertEquals("new-access-token", service.getAccessToken())
        assertIs<AuthState.Success>(service.authState.value)

        repository.authHeadersCanFinish.complete(Unit)

        assertEquals(emptyMap(), headers.await())
        advanceUntilIdle()
        assertEquals("new-access-token", service.getAccessToken())
        assertIs<AuthState.Success>(service.authState.value)
    }

    @Test
    fun `duplicate login failure is shared and later login can recover`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = null,
            loginAccessToken = "new-access-token",
            currentUser = null,
            suspendFirstLoginAndFail = true
        )
        val service = AuthService(repository)
        advanceUntilIdle()

        val staleLogin = async {
            runCatching { service.login() }
        }
        runCurrent()
        assertTrue(repository.firstLoginStarted.isCompleted)

        val duplicateLogin = async {
            runCatching { service.login() }
        }
        runCurrent()
        assertFalse(duplicateLogin.isCompleted)

        repository.firstLoginCanFail.complete(Unit)

        assertTrue(staleLogin.await().isFailure)
        assertTrue(duplicateLogin.await().isFailure)
        advanceUntilIdle()

        val errorState = assertIs<AuthState.Error>(service.authState.value)
        assertEquals("stale login failed", errorState.message)

        service.login()

        assertEquals("new-access-token", service.getAccessToken())
        assertIs<AuthState.Success>(service.authState.value)
    }

    @Test
    fun `reauthentication waits for active normal login then runs reauthentication separately`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = null,
                loginAccessToken = "normal-access-token",
                reauthenticationAccessToken = "reauth-access-token",
                currentUser = null,
                suspendFirstLogin = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()

            val normalLogin = async {
                service.login()
            }
            runCurrent()
            assertTrue(repository.firstLoginStarted.isCompleted)

            val reauthentication = async {
                service.loginWithReauthentication()
            }
            runCurrent()
            assertFalse(reauthentication.isCompleted)

            repository.firstLoginCanFinish.complete(Unit)

            normalLogin.await()
            reauthentication.await()
            advanceUntilIdle()

            assertEquals(1, repository.loginCalls)
            assertEquals(1, repository.reauthenticationCalls)
            assertEquals("reauth-access-token", service.getAccessToken())
            assertIs<AuthState.Success>(service.authState.value)
        }

    @Test
    fun `reauthentication waits through active normal login cancellation and starts its own prompt`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = null,
                loginAccessToken = "normal-access-token",
                reauthenticationAccessToken = "reauth-access-token",
                currentUser = null,
                suspendFirstLoginAndCancel = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()

            val normalLogin = async {
                runCatching { service.login() }
            }
            runCurrent()
            assertTrue(repository.firstLoginStarted.isCompleted)

            val reauthentication = async {
                runCatching { service.loginWithReauthentication() }
            }
            runCurrent()
            assertFalse(reauthentication.isCompleted)

            repository.firstLoginCanFail.complete(Unit)

            assertTrue(normalLogin.await().isFailure)
            assertTrue(reauthentication.await().isSuccess)
            advanceUntilIdle()

            assertEquals(1, repository.loginCalls)
            assertEquals(1, repository.reauthenticationCalls)
            assertEquals("reauth-access-token", service.getAccessToken())
            assertIs<AuthState.Success>(service.authState.value)
        }

    @Test
    fun `cancelled active login releases duplicate service login waiter`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = null,
                loginAccessToken = "normal-access-token",
                currentUser = null,
                suspendFirstLoginAndCancel = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()

            val normalLogin = async {
                runCatching { service.login() }
            }
            runCurrent()
            assertTrue(repository.firstLoginStarted.isCompleted)

            val duplicateLogin = async {
                runCatching { service.login() }
            }
            runCurrent()
            assertFalse(duplicateLogin.isCompleted)

            repository.firstLoginCanFail.complete(Unit)

            assertTrue(normalLogin.await().isFailure)
            assertTrue(duplicateLogin.await().isFailure)
            advanceUntilIdle()

            assertEquals(1, repository.loginCalls)
            assertNull(service.getAccessToken())
        }

    @Test
    fun `cancelled pre commit login waiter fails while old session exists`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = "old-access-token",
                loginAccessToken = "new-access-token",
                currentUser = null,
                suspendFirstLoginAndCancel = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()
            assertEquals("old-access-token", service.getAccessToken())

            val firstLogin = async {
                runCatching { service.login() }
            }
            runCurrent()
            assertTrue(repository.firstLoginStarted.isCompleted)

            val duplicateLogin = async {
                runCatching { service.login() }
            }
            runCurrent()
            assertFalse(duplicateLogin.isCompleted)

            repository.firstLoginCanFail.complete(Unit)

            assertTrue(firstLogin.await().isFailure)
            assertTrue(duplicateLogin.await().isFailure)
            advanceUntilIdle()

            assertNull(service.getAccessToken())
            assertIs<AuthState.Idle>(service.authState.value)
        }

    @Test
    fun `cancelled pre commit reauthentication waiter fails while old session exists`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = "old-access-token",
                reauthenticationAccessToken = "reauth-access-token",
                currentUser = null,
                suspendFirstReauthenticationAndCancel = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()
            assertEquals("old-access-token", service.getAccessToken())

            val firstReauthentication = async {
                runCatching { service.loginWithReauthentication() }
            }
            runCurrent()
            assertTrue(repository.firstReauthenticationStarted.isCompleted)

            val duplicateReauthentication = async {
                runCatching { service.loginWithReauthentication() }
            }
            runCurrent()
            assertFalse(duplicateReauthentication.isCompleted)

            repository.firstReauthenticationCanFail.complete(Unit)

            assertTrue(firstReauthentication.await().isFailure)
            assertTrue(duplicateReauthentication.await().isFailure)
            advanceUntilIdle()

            assertNull(service.getAccessToken())
            assertIs<AuthState.Idle>(service.authState.value)
        }

    @Test
    fun `cancelled active login publishes idle state`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = null,
                loginAccessToken = "normal-access-token",
                currentUser = null,
                suspendFirstLoginAndCancel = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()

            val login = async {
                runCatching { service.login() }
            }
            runCurrent()
            assertTrue(repository.firstLoginStarted.isCompleted)
            assertIs<AuthState.Loading>(service.authState.value)

            repository.firstLoginCanFail.complete(Unit)

            assertTrue(login.await().isFailure)
            advanceUntilIdle()
            assertNull(service.getAccessToken())
            assertIs<AuthState.Idle>(service.authState.value)
        }

    @Test
    fun `cancelled post login validation publishes committed session during cleanup`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = null,
                loginAccessToken = "login-access-token",
                currentUser = null,
                refreshFailure = CancellationException("validation cancelled"),
                refreshFailureOnCall = 2
            )
            val service = AuthService(repository)
            advanceUntilIdle()

            val login = runCatching {
                service.login()
            }

            assertTrue(login.isFailure)
            assertTrue(service.isLoggedIn())
            assertEquals("login-access-token", service.getAccessToken())
            assertIs<AuthState.Success>(service.authState.value)
            assertEquals(
                mapOf("Authorization" to "Bearer login-access-token"),
                service.getAuthHeaders()
            )
        }

    @Test
    fun `post commit login cancellation returns idle then lazily publishes committed session`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = null,
                loginAccessToken = "login-access-token",
                currentUser = null,
                suspendCurrentUser = true,
                failCurrentUserOnce = CancellationException("publication cancelled")
            )
            val service = AuthService(repository)
            advanceUntilIdle()

            val firstLogin = async {
                runCatching { service.login() }
            }
            runCurrent()
            assertTrue(repository.currentUserStarted.isCompleted)

            val duplicateLogin = async {
                runCatching { service.login() }
            }
            runCurrent()
            assertFalse(duplicateLogin.isCompleted)

            repository.currentUserCanFinish.complete(Unit)

            assertTrue(firstLogin.await().isFailure)
            assertTrue(duplicateLogin.await().isSuccess)
            advanceUntilIdle()

            assertEquals(
                mapOf("Authorization" to "Bearer login-access-token"),
                service.getAuthHeaders()
            )
            assertTrue(service.isLoggedIn())
            assertEquals("login-access-token", service.getAccessToken())
            assertIs<AuthState.Success>(service.authState.value)
        }

    @Test
    fun `post commit login cancellation with reused token can lazily publish committed session`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = "same-access-token",
                loginAccessToken = "same-access-token",
                currentUser = null,
                suspendCurrentUser = true,
                suspendCurrentUserOnCall = 2,
                failCurrentUserOnce = CancellationException("publication cancelled"),
                failCurrentUserOnCall = 2
            )
            val service = AuthService(repository)
            advanceUntilIdle()
            assertEquals("same-access-token", service.getAccessToken())

            val firstLogin = async {
                runCatching { service.login() }
            }
            runCurrent()
            assertTrue(repository.currentUserStarted.isCompleted)

            val duplicateLogin = async {
                runCatching { service.login() }
            }
            runCurrent()
            assertFalse(duplicateLogin.isCompleted)

            repository.currentUserCanFinish.complete(Unit)

            assertTrue(firstLogin.await().isFailure)
            assertTrue(duplicateLogin.await().isSuccess)
            advanceUntilIdle()

            assertEquals(
                mapOf("Authorization" to "Bearer same-access-token"),
                service.getAuthHeaders()
            )
            assertTrue(service.isLoggedIn())
            assertEquals("same-access-token", service.getAccessToken())
            assertIs<AuthState.Success>(service.authState.value)
        }

    @Test
    fun `duplicate login waiter succeeds when caller cancellation follows durable repository commit`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = "same-access-token",
                loginAccessToken = "same-access-token",
                currentUser = null,
                suspendFirstLoginAfterTokenWrite = true,
                ignoreFirstLoginCancellation = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()
            assertEquals("same-access-token", service.getAccessToken())

            val firstLogin = launch {
                service.login()
            }
            runCurrent()
            assertTrue(repository.firstLoginAfterTokenWriteStarted.isCompleted)

            val duplicateLogin = async {
                runCatching { service.login() }
            }
            runCurrent()
            assertFalse(duplicateLogin.isCompleted)

            firstLogin.cancel()
            runCurrent()
            assertTrue(repository.firstLoginCancellationObserved.isCompleted)

            repository.firstLoginAfterTokenWriteCanFinish.complete(Unit)
            firstLogin.join()

            assertTrue(duplicateLogin.await().isSuccess)
            assertTrue(service.isLoggedIn())
            assertEquals("same-access-token", service.getAccessToken())
            assertIs<AuthState.Success>(service.authState.value)
            assertEquals(
                mapOf("Authorization" to "Bearer same-access-token"),
                service.getAuthHeaders()
            )
        }

    @Test
    fun `duplicate login waiter succeeds when caller cancels before durable commit callback returns`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = "same-access-token",
                loginAccessToken = "same-access-token",
                currentUser = null,
                suspendFirstLoginBeforeDurableCommitCallback = true,
                ignoreFirstLoginCancellation = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()
            assertEquals("same-access-token", service.getAccessToken())

            val firstLogin = launch {
                service.login()
            }
            runCurrent()
            assertTrue(repository.firstLoginBeforeDurableCommitCallbackStarted.isCompleted)

            val duplicateLogin = async {
                runCatching { service.login() }
            }
            runCurrent()
            assertFalse(duplicateLogin.isCompleted)

            firstLogin.cancel()
            runCurrent()
            assertTrue(repository.firstLoginCancellationObserved.isCompleted)

            repository.firstLoginBeforeDurableCommitCallbackCanFinish.complete(Unit)
            firstLogin.join()

            assertTrue(duplicateLogin.await().isSuccess)
            assertTrue(service.isLoggedIn())
            assertEquals("same-access-token", service.getAccessToken())
            assertIs<AuthState.Success>(service.authState.value)
            assertEquals(
                mapOf("Authorization" to "Bearer same-access-token"),
                service.getAuthHeaders()
            )
        }

    @Test
    fun `duplicate login waiter fails when cleanup cannot publish committed login`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = "old-access-token",
                loginAccessToken = "new-access-token",
                currentUser = null,
                suspendFirstLoginAfterTokenWrite = true,
                ignoreFirstLoginCancellation = true,
                refreshFailure = CancellationException("cleanup publication cancelled"),
                refreshFailureOnCall = 2
            )
            val service = AuthService(repository)
            advanceUntilIdle()
            assertEquals("old-access-token", service.getAccessToken())

            val firstLogin = launch {
                service.login()
            }
            runCurrent()
            assertTrue(repository.firstLoginAfterTokenWriteStarted.isCompleted)

            val duplicateLogin = async {
                runCatching { service.login() }
            }
            runCurrent()
            assertFalse(duplicateLogin.isCompleted)

            firstLogin.cancel()
            runCurrent()
            assertTrue(repository.firstLoginCancellationObserved.isCompleted)

            repository.firstLoginAfterTokenWriteCanFinish.complete(Unit)
            firstLogin.join()

            val duplicateResult = withTimeout(1_000) {
                duplicateLogin.await()
            }
            assertTrue(duplicateResult.isFailure)
            assertNull(service.getAccessToken())
            assertIs<AuthState.Idle>(service.authState.value)
        }

    @Test
    fun `duplicate login waiter fails promptly when local clear invalidates active login`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = null,
                loginAccessToken = "stale-access-token",
                currentUser = null,
                suspendFirstLogin = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()

            val firstLogin = async {
                runCatching { service.login() }
            }
            runCurrent()
            assertTrue(repository.firstLoginStarted.isCompleted)

            val duplicateLogin = async {
                runCatching { service.login() }
            }
            runCurrent()
            assertFalse(duplicateLogin.isCompleted)

            service.clearLocalSession()
            runCurrent()

            assertTrue(duplicateLogin.await().isFailure)

            repository.firstLoginCanFinish.complete(Unit)

            assertTrue(firstLogin.await().isFailure)
            advanceUntilIdle()
            assertNull(service.getAccessToken())
            assertIs<AuthState.Idle>(service.authState.value)
        }

    @Test
    fun `different intent reauthentication waiter fails after local clear without starting reauth`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = null,
                loginAccessToken = "stale-access-token",
                reauthenticationAccessToken = "reauth-access-token",
                currentUser = null,
                suspendFirstLogin = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()

            val firstLogin = async {
                runCatching { service.login() }
            }
            runCurrent()
            assertTrue(repository.firstLoginStarted.isCompleted)

            val reauthentication = async {
                runCatching { service.loginWithReauthentication() }
            }
            runCurrent()
            assertFalse(reauthentication.isCompleted)

            service.clearLocalSession()
            runCurrent()

            assertTrue(reauthentication.await().isFailure)
            assertEquals(0, repository.reauthenticationCalls)

            repository.firstLoginCanFinish.complete(Unit)

            assertTrue(firstLogin.await().isFailure)
            advanceUntilIdle()
            assertNull(service.getAccessToken())
            assertIs<AuthState.Idle>(service.authState.value)
        }

    @Test
    fun `original active login cannot publish success after local clear wins`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = null,
                loginAccessToken = "stale-access-token",
                currentUser = null,
                suspendCurrentUser = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()

            val login = async {
                runCatching { service.login() }
            }
            runCurrent()
            assertTrue(repository.currentUserStarted.isCompleted)

            service.clearLocalSession()
            assertNull(repository.getAccessToken())
            assertNull(service.getAccessToken())
            assertIs<AuthState.Idle>(service.authState.value)

            repository.currentUserCanFinish.complete(Unit)

            assertTrue(login.await().isFailure)
            advanceUntilIdle()
            assertNull(service.getAccessToken())
            assertIs<AuthState.Idle>(service.authState.value)
        }

    @Test
    fun `startup check does not disturb active explicit login loading state`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = null,
                loginAccessToken = "login-access-token",
                currentUser = null,
                suspendFirstLogin = true
            )
            val service = AuthService(repository)

            advanceUntilIdle()
            val login = async {
                service.login()
            }
            runCurrent()
            assertTrue(repository.firstLoginStarted.isCompleted)
            assertIs<AuthState.Loading>(service.authState.value)

            repository.firstLoginCanFinish.complete(Unit)
            login.await()
            advanceUntilIdle()

            assertEquals("login-access-token", service.getAccessToken())
            assertIs<AuthState.Success>(service.authState.value)
        }

    @Test
    fun `auth headers during active login loading return empty and do not fail login`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = null,
                loginAccessToken = "login-access-token",
                currentUser = null,
                suspendFirstLogin = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()

            val login = async {
                service.login()
            }
            runCurrent()
            assertTrue(repository.firstLoginStarted.isCompleted)
            assertIs<AuthState.Loading>(service.authState.value)

            assertEquals(emptyMap(), service.getAuthHeaders())
            assertIs<AuthState.Loading>(service.authState.value)

            repository.firstLoginCanFinish.complete(Unit)
            login.await()
            advanceUntilIdle()

            assertEquals("login-access-token", service.getAccessToken())
            assertIs<AuthState.Success>(service.authState.value)
        }

    @Test
    fun `refresh false during active login loading state does not publish idle`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = "old-access-token",
                loginAccessToken = "login-access-token",
                currentUser = null,
                refreshResults = listOf(true, false, true),
                suspendRefreshOnCalls = setOf(2),
                suspendFirstLogin = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()

            val refresh = async {
                service.refreshAccessTokenIfNeeded()
            }
            runCurrent()
            assertTrue(repository.refreshStarted.isCompleted)

            val login = async {
                service.login()
            }
            runCurrent()
            assertTrue(repository.firstLoginStarted.isCompleted)
            assertIs<AuthState.Loading>(service.authState.value)

            repository.refreshCanFinish.complete(Unit)

            assertFalse(refresh.await())
            runCurrent()
            assertIs<AuthState.Loading>(service.authState.value)

            repository.firstLoginCanFinish.complete(Unit)
            login.await()
            advanceUntilIdle()

            assertEquals("login-access-token", service.getAccessToken())
            assertIs<AuthState.Success>(service.authState.value)
        }

    @Test
    fun `refresh commit failure reconciles published auth with repository state`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = "old-access-token",
                currentUser = null,
                refreshFailure = IllegalStateException("refresh commit failed"),
                refreshFailureOnCall = 2,
                clearTokenBeforeRefreshFailure = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()
            assertEquals("old-access-token", service.getAccessToken())

            val result = runCatching {
                service.refreshAccessTokenIfNeeded()
            }

            assertTrue(result.isFailure)
            assertNull(service.getAccessToken())
            assertIs<AuthState.Idle>(service.authState.value)
        }

    @Test
    fun `refresh success with hidden repository session clears current published auth`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = "old-access-token",
                currentUser = null,
                refreshResult = true,
                clearTokenBeforeRefreshSuccess = true,
                clearTokenBeforeRefreshSuccessOnCall = 2
            )
            val service = AuthService(repository)
            advanceUntilIdle()
            assertEquals("old-access-token", service.getAccessToken())

            assertTrue(service.refreshAccessTokenIfNeeded())

            assertNull(service.getAccessToken())
            assertIs<AuthState.Idle>(service.authState.value)
        }

    @Test
    fun `duplicate login while first exchange is active publishes the successful session`() =
        runTest(dispatcher) {
            val repository = RecordingAuthRepository(
                accessToken = null,
                loginAccessToken = "login-access-token",
                currentUser = null,
                suspendFirstLogin = true,
                duplicateLoginReturnsWithoutTokenWhileFirstSuspended = true
            )
            val service = AuthService(repository)
            advanceUntilIdle()

            val firstLogin = async {
                runCatching { service.login() }
            }
            runCurrent()
            assertTrue(repository.firstLoginStarted.isCompleted)

            val duplicateLogin = async {
                runCatching { service.login() }
            }
            runCurrent()

            assertFalse(duplicateLogin.isCompleted)

            repository.firstLoginCanFinish.complete(Unit)

            assertTrue(firstLogin.await().isSuccess)
            assertTrue(duplicateLogin.await().isSuccess)
            advanceUntilIdle()

            val state = assertIs<AuthState.Success>(service.authState.value)
            assertNull(state.userInfo)
            assertTrue(service.isLoggedIn())
            assertEquals("login-access-token", service.getAccessToken())
            assertEquals(
                mapOf("Authorization" to "Bearer login-access-token"),
                service.getAuthHeaders()
            )
        }

    @Test
    fun `current login error publishes error`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository(
            accessToken = null,
            currentUser = null,
            loginFailure = IllegalStateException("login failed")
        )
        val service = AuthService(repository)
        advanceUntilIdle()

        val result = runCatching { service.login() }

        assertTrue(result.isFailure)
        val state = assertIs<AuthState.Error>(service.authState.value)
        assertEquals("login failed", state.message)
    }
}

private class RecordingAuthRepository(
    private var accessToken: String?,
    private val loginAccessToken: String? = accessToken,
    private val reauthenticationAccessToken: String? = loginAccessToken,
    private val currentUser: UserInfo?,
    private val throwAfterLocalLogoutClear: Boolean = false,
    private val throwOnClearLocalSession: Boolean = false,
    private val suspendRemoteLogout: Boolean = false,
    private val throwOnLogoutTokenCapture: Boolean = false,
    private val suspendCurrentUser: Boolean = false,
    private val refreshResult: Boolean? = null,
    private val suspendRefresh: Boolean = false,
    private val suspendRefreshOnCalls: Set<Int>? = null,
    private val refreshResults: List<Boolean>? = null,
    private val refreshFailure: Exception? = null,
    private val refreshFailureOnCall: Int? = null,
    private val clearTokenBeforeRefreshFailure: Boolean = false,
    private val clearTokenBeforeRefreshSuccess: Boolean = false,
    private val clearTokenBeforeRefreshSuccessOnCall: Int? = null,
    private val forcedAuthHeaders: Map<String, String>? = null,
    private val suspendAuthHeaders: Boolean = false,
    private val authHeadersFailure: Exception? = null,
    private val clearTokenBeforeAuthHeadersFailure: Boolean = false,
    private val suspendFirstLogin: Boolean = false,
    private val suspendFirstLoginBeforeDurableCommitCallback: Boolean = false,
    private val suspendFirstLoginAfterTokenWrite: Boolean = false,
    private val duplicateLoginReturnsWithoutTokenWhileFirstSuspended: Boolean = false,
    private val suspendFirstLoginAndFail: Boolean = false,
    private val suspendFirstLoginAndCancel: Boolean = false,
    private val suspendFirstReauthenticationAndCancel: Boolean = false,
    private val ignoreFirstLoginCancellation: Boolean = false,
    private val loginFailure: Exception? = null,
    private val suspendFirstAccessTokenRead: Boolean = false,
    private val suspendLogoutTokenCapture: Boolean = false,
    private val remoteLogoutException: Exception? = null,
    private val replaceSessionBeforeLogoutTokenCaptureFailure: Boolean = false,
    private val suspendBeforeLocalLogoutClear: Boolean = false,
    private val suspendCurrentUserOnCall: Int? = null,
    private val suspendSecondIsLoggedInRead: Boolean = false,
    private val suspendThirdAccessTokenRead: Boolean = false,
    private val accessTokenAfterCurrentUserRead: String? = null,
    private val failCurrentUserOnce: Exception? = null,
    private val failCurrentUserOnCall: Int = 1
) : AuthRepositoryLoginCommitCallbacks {
    val remoteLogoutStarted = CompletableDeferred<Unit>()
    val remoteLogoutCanFinish = CompletableDeferred<Unit>()
    val logoutTokenCaptureStarted = CompletableDeferred<Unit>()
    val logoutTokenCaptureCanFinish = CompletableDeferred<Unit>()
    val currentUserStarted = CompletableDeferred<Unit>()
    val currentUserCanFinish = CompletableDeferred<Unit>()
    val refreshStarted = CompletableDeferred<Unit>()
    val refreshCanFinish = CompletableDeferred<Unit>()
    val authHeadersStarted = CompletableDeferred<Unit>()
    val authHeadersCanFinish = CompletableDeferred<Unit>()
    val firstLoginStarted = CompletableDeferred<Unit>()
    val firstLoginCanFinish = CompletableDeferred<Unit>()
    val firstLoginBeforeDurableCommitCallbackStarted = CompletableDeferred<Unit>()
    val firstLoginBeforeDurableCommitCallbackCanFinish = CompletableDeferred<Unit>()
    val firstLoginAfterTokenWriteStarted = CompletableDeferred<Unit>()
    val firstLoginAfterTokenWriteCanFinish = CompletableDeferred<Unit>()
    val firstLoginCanFail = CompletableDeferred<Unit>()
    val firstReauthenticationStarted = CompletableDeferred<Unit>()
    val firstReauthenticationCanFail = CompletableDeferred<Unit>()
    val firstLoginCancellationObserved = CompletableDeferred<Unit>()
    val firstAccessTokenReadStarted = CompletableDeferred<Unit>()
    val firstAccessTokenReadCanFinish = CompletableDeferred<Unit>()
    val thirdAccessTokenReadStarted = CompletableDeferred<Unit>()
    val thirdAccessTokenReadCanFinish = CompletableDeferred<Unit>()
    val secondIsLoggedInReadStarted = CompletableDeferred<Unit>()
    val secondIsLoggedInReadCanFinish = CompletableDeferred<Unit>()
    var clearLocalSessionCalls = 0
        private set
    var loginCalls = 0
        private set
    var reauthenticationCalls = 0
        private set
    var refreshCalls = 0
        private set
    private var accessTokenReadCalls = 0
    private var isLoggedInReadCalls = 0
    private var currentUserCalls = 0
    private var currentUserFailureThrown = false

    override suspend fun login() {
        login(onDurableCommit = {})
    }

    override suspend fun login(onDurableCommit: suspend () -> Unit) {
        loginCalls += 1
        if (
            duplicateLoginReturnsWithoutTokenWhileFirstSuspended &&
            loginCalls > 1 &&
            !firstLoginCanFinish.isCompleted
        ) {
            return
        }
        if (suspendFirstLogin && loginCalls == 1) {
            firstLoginStarted.complete(Unit)
            try {
                firstLoginCanFinish.await()
            } catch (e: CancellationException) {
                if (!ignoreFirstLoginCancellation) {
                    throw e
                }
                firstLoginCancellationObserved.complete(Unit)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    firstLoginCanFinish.await()
                }
            }
        }
        if (suspendFirstLoginAndFail && loginCalls == 1) {
            firstLoginStarted.complete(Unit)
            firstLoginCanFail.await()
            throw IllegalStateException("stale login failed")
        }
        if (suspendFirstLoginAndCancel && loginCalls == 1) {
            firstLoginStarted.complete(Unit)
            firstLoginCanFail.await()
            throw CancellationException("normal login cancelled")
        }
        loginFailure?.let { throw it }
        accessToken = loginAccessToken
        if (suspendFirstLoginBeforeDurableCommitCallback && loginCalls == 1) {
            firstLoginBeforeDurableCommitCallbackStarted.complete(Unit)
            try {
                firstLoginBeforeDurableCommitCallbackCanFinish.await()
            } catch (e: CancellationException) {
                if (!ignoreFirstLoginCancellation) {
                    throw e
                }
                firstLoginCancellationObserved.complete(Unit)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    firstLoginBeforeDurableCommitCallbackCanFinish.await()
                }
            }
        }
        onDurableCommit()
        if (suspendFirstLoginAfterTokenWrite && loginCalls == 1) {
            firstLoginAfterTokenWriteStarted.complete(Unit)
            try {
                firstLoginAfterTokenWriteCanFinish.await()
            } catch (e: CancellationException) {
                if (!ignoreFirstLoginCancellation) {
                    throw e
                }
                firstLoginCancellationObserved.complete(Unit)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    firstLoginAfterTokenWriteCanFinish.await()
                }
            }
        }
    }

    override suspend fun loginWithReauthentication() {
        loginWithReauthentication(onDurableCommit = {})
    }

    override suspend fun loginWithReauthentication(onDurableCommit: suspend () -> Unit) {
        reauthenticationCalls += 1
        if (suspendFirstReauthenticationAndCancel && reauthenticationCalls == 1) {
            firstReauthenticationStarted.complete(Unit)
            firstReauthenticationCanFail.await()
            throw CancellationException("reauthentication cancelled")
        }
        accessToken = reauthenticationAccessToken
        onDurableCommit()
    }

    override suspend fun refreshTokensIfNeeded(): Boolean {
        refreshCalls += 1
        val refreshCall = refreshCalls
        val shouldSuspend = suspendRefreshOnCalls?.contains(refreshCall) ?: suspendRefresh
        if (shouldSuspend) {
            refreshStarted.complete(Unit)
            refreshCanFinish.await()
        }
        val failure = refreshFailure
        if (failure != null && (refreshFailureOnCall == null || refreshFailureOnCall == refreshCall)) {
            if (clearTokenBeforeRefreshFailure) {
                accessToken = null
            }
            throw failure
        }
        if (
            clearTokenBeforeRefreshSuccess &&
            (clearTokenBeforeRefreshSuccessOnCall == null || clearTokenBeforeRefreshSuccessOnCall == refreshCall)
        ) {
            accessToken = null
        }
        val sequencedResult = refreshResults?.getOrNull(refreshCall - 1)
        return sequencedResult ?: refreshResult ?: (accessToken != null)
    }

    override suspend fun logout() {
        accessToken = null
        if (throwAfterLocalLogoutClear) {
            throw IllegalStateException("remote logout failed")
        }
    }

    override suspend fun captureLogoutTokenMaterial(): LogoutTokenMaterial {
        if (suspendLogoutTokenCapture) {
            logoutTokenCaptureStarted.complete(Unit)
            logoutTokenCaptureCanFinish.await()
        }
        if (throwOnLogoutTokenCapture) {
            throw IllegalStateException("token capture failed")
        }
        return LogoutTokenMaterial(refreshToken = accessToken, idToken = null)
    }

    override suspend fun captureLogoutTokenMaterialAndClearLocalSession(): LogoutTokenMaterial {
        val tokenMaterial = LogoutTokenMaterial(refreshToken = accessToken, idToken = null)
        if (suspendBeforeLocalLogoutClear) {
            logoutTokenCaptureStarted.complete(Unit)
            logoutTokenCaptureCanFinish.await()
        }
        clearLocalSession()
        if (suspendLogoutTokenCapture) {
            logoutTokenCaptureStarted.complete(Unit)
            logoutTokenCaptureCanFinish.await()
        }
        if (throwOnLogoutTokenCapture) {
            if (replaceSessionBeforeLogoutTokenCaptureFailure) {
                accessToken = loginAccessToken
            }
            throw LogoutTokenCaptureException(IllegalStateException("token capture failed"))
        }
        return tokenMaterial
    }

    override suspend fun clearLocalSession() {
        clearLocalSessionCalls += 1
        accessToken = null
        if (throwOnClearLocalSession) {
            throw IllegalStateException("local clear failed")
        }
    }

    override suspend fun attemptRemoteLogout(tokenMaterial: LogoutTokenMaterial): List<RemoteLogoutFailure> {
        remoteLogoutStarted.complete(Unit)
        if (suspendRemoteLogout) {
            remoteLogoutCanFinish.await()
        }
        remoteLogoutException?.let { throw it }
        if (!throwAfterLocalLogoutClear) {
            return emptyList()
        }
        return listOf(
            RemoteLogoutFailure(
                operation = RemoteLogoutOperation.REVOKE_REFRESH_TOKEN,
                exception = IllegalStateException("remote logout failed")
            )
        )
    }

    override suspend fun getAccessToken(): String? {
        accessTokenReadCalls += 1
        if (suspendFirstAccessTokenRead && accessTokenReadCalls == 1) {
            val capturedAccessToken = accessToken
            firstAccessTokenReadStarted.complete(Unit)
            firstAccessTokenReadCanFinish.await()
            return capturedAccessToken
        }
        if (suspendThirdAccessTokenRead && accessTokenReadCalls == 3) {
            val capturedAccessToken = accessToken
            thirdAccessTokenReadStarted.complete(Unit)
            thirdAccessTokenReadCanFinish.await()
            return capturedAccessToken
        }
        return accessToken
    }

    override suspend fun isLoggedIn(): Boolean {
        isLoggedInReadCalls += 1
        if (suspendSecondIsLoggedInRead && isLoggedInReadCalls == 2) {
            secondIsLoggedInReadStarted.complete(Unit)
            secondIsLoggedInReadCanFinish.await()
        }
        return accessToken != null
    }

    override suspend fun getCurrentUser(): UserInfo? {
        currentUserCalls += 1
        if (
            suspendCurrentUser &&
            (suspendCurrentUserOnCall == null || currentUserCalls == suspendCurrentUserOnCall)
        ) {
            currentUserStarted.complete(Unit)
            currentUserCanFinish.await()
        }
        if (
            failCurrentUserOnce != null &&
            currentUserCalls >= failCurrentUserOnCall &&
            !currentUserFailureThrown
        ) {
            currentUserFailureThrown = true
            throw failCurrentUserOnce
        }
        accessTokenAfterCurrentUserRead?.let { accessToken = it }
        return currentUser
    }

    override suspend fun getAuthHeaders(): Map<String, String> {
        if (suspendAuthHeaders) {
            authHeadersStarted.complete(Unit)
            authHeadersCanFinish.await()
        }
        authHeadersFailure?.let { failure ->
            if (clearTokenBeforeAuthHeadersFailure) {
                accessToken = null
            }
            throw failure
        }
        forcedAuthHeaders?.let { return it }
        val token = accessToken ?: return emptyMap()
        return mapOf("Authorization" to "Bearer $token")
    }

}
