package com.quran.shared.auth.service

import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.LogoutTokenMaterial
import com.quran.shared.auth.repository.RemoteLogoutFailure
import com.quran.shared.auth.repository.RemoteLogoutMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The launch check: [AuthService.authState] is [AuthState.Restoring] until the stored session was read, which is then
 * published from local storage before its tokens are refreshed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthServiceRestoreTest {
    private val dispatcher = StandardTestDispatcher()
    private val storedUser = UserInfo(id = "user-1", name = "Stored User")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the state is restoring until the stored session was read`() = runTest(dispatcher) {
        val service = AuthService(StoredSessionAuthRepository(accessToken = "stored", user = storedUser))

        assertEquals(AuthState.Restoring, service.authState.value)
        assertFalse(service.isLoggedIn())
    }

    @Test
    fun `no stored session ends restoring as signed out`() = runTest(dispatcher) {
        val service = AuthService(StoredSessionAuthRepository(accessToken = null, user = null))
        assertEquals(AuthState.Restoring, service.authState.value)

        advanceUntilIdle()

        assertEquals(AuthState.Idle, service.authState.value)
    }

    @Test
    fun `a stored session with an expired token is published before its refresh completes`() = runTest(dispatcher) {
        val repository = StoredSessionAuthRepository(accessToken = "expired", user = storedUser, suspendRefresh = true)
        val service = AuthService(repository)

        runCurrent()

        assertTrue(repository.refreshStarted.isCompleted, "the refresh is waiting on the network")
        assertEquals(AuthState.Success(storedUser), service.authState.value)
        assertEquals("expired", service.getAccessToken())

        repository.refreshCanFinish.complete(Unit)
        advanceUntilIdle()

        assertEquals(AuthState.Success(storedUser), service.authState.value)
        assertEquals("refreshed", service.getAccessToken())
    }

    @Test
    fun `a transient refresh failure at launch keeps the stored session`() = runTest(dispatcher) {
        val repository = StoredSessionAuthRepository(
            accessToken = "expired",
            user = storedUser,
            refreshFailure = IOException("offline")
        )
        val service = AuthService(repository)

        advanceUntilIdle()

        assertEquals(1, repository.refreshCalls)
        assertEquals(AuthState.Success(storedUser), service.authState.value)
        assertTrue(service.isLoggedIn())
        assertEquals("expired", service.getAccessToken())
    }

    @Test
    fun `a refused refresh token at launch ends the stored session`() = runTest(dispatcher) {
        val repository = StoredSessionAuthRepository(accessToken = "expired", user = storedUser, refreshAccepted = false)
        val service = AuthService(repository)

        advanceUntilIdle()

        assertEquals(1, repository.refreshCalls)
        assertEquals(AuthState.Idle, service.authState.value)
        assertFalse(service.isLoggedIn())
        assertNull(service.getAccessToken())
    }

    @Test
    fun `a service cleared before its launch check ends restoring`() = runTest(dispatcher) {
        val service = AuthService(StoredSessionAuthRepository(accessToken = "stored", user = storedUser))

        service.clearAndJoin()

        assertEquals(AuthState.Idle, service.authState.value)
    }
}

/**
 * A stored session whose token is always due for a refresh: an accepted refresh stores a new token, a refused one ends
 * the session (as the authorization server's refusal does), and a transient failure is thrown and keeps it.
 */
private class StoredSessionAuthRepository(
    private var accessToken: String?,
    private val user: UserInfo?,
    private val refreshAccepted: Boolean = true,
    private val refreshFailure: Exception? = null,
    private val suspendRefresh: Boolean = false
) : AuthRepository {
    val refreshStarted = CompletableDeferred<Unit>()
    val refreshCanFinish = CompletableDeferred<Unit>()
    var refreshCalls = 0
        private set

    override suspend fun login() {
        accessToken = "signed-in"
    }

    override suspend fun refreshTokensIfNeeded(): Boolean {
        if (accessToken == null) return false
        refreshCalls += 1
        if (suspendRefresh) {
            refreshStarted.complete(Unit)
            refreshCanFinish.await()
        }
        refreshFailure?.let { throw it }
        accessToken = if (refreshAccepted) "refreshed" else null
        return refreshAccepted
    }

    override suspend fun logout() = clearLocalSession()

    override suspend fun captureLogoutTokenMaterial() = LogoutTokenMaterial(refreshToken = null, idToken = null)

    override suspend fun clearLocalSession() {
        accessToken = null
    }

    override suspend fun attemptRemoteLogout(tokenMaterial: LogoutTokenMaterial, mode: RemoteLogoutMode): List<RemoteLogoutFailure> =
        emptyList()

    override suspend fun getAccessToken(): String? = accessToken

    override suspend fun isLoggedIn(): Boolean = accessToken != null

    override suspend fun getCurrentUser(): UserInfo? = user.takeIf { accessToken != null }

    override suspend fun getAuthHeaders(): Map<String, String> =
        if (refreshTokensIfNeeded()) mapOf("Authorization" to "Bearer $accessToken") else emptyMap()
}
