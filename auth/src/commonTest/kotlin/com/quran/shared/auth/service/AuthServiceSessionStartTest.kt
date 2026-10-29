package com.quran.shared.auth.service

import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.LogoutTokenMaterial
import com.quran.shared.auth.repository.RemoteLogoutFailure
import com.quran.shared.auth.repository.RemoteLogoutMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [AuthService.sessionStart]: how each session began, once per session, whatever publishes it again later. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthServiceSessionStartTest {
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
    fun `a stored session restored at launch is not interactive`() = runTest(dispatcher) {
        val service = AuthService(TokenAuthRepository(accessToken = "stored"))
        assertNull(service.sessionStart.value, "nothing published yet")

        advanceUntilIdle()

        assertIs<AuthState.Success>(service.authState.value)
        assertEquals(false, service.sessionStart.value?.interactive)
    }

    @Test
    fun `a sign-in is interactive and a new session`() = runTest(dispatcher) {
        val service = AuthService(TokenAuthRepository(accessToken = "stored"))
        advanceUntilIdle()
        val restored = service.sessionStart.value

        service.login()

        val signedIn = service.sessionStart.value
        assertEquals(true, signedIn?.interactive)
        assertNotEquals(restored?.generation, signedIn?.generation)
    }

    @Test
    fun `a token refresh publishing the session again keeps its start`() = runTest(dispatcher) {
        val repository = TokenAuthRepository(accessToken = null)
        val service = AuthService(repository)
        advanceUntilIdle()
        service.login()
        val signedIn = service.sessionStart.value

        repository.currentUser = UserInfo(id = "user-1", name = "Renamed")
        assertTrue(service.forceRefreshAccessToken())

        assertEquals(UserInfo(id = "user-1", name = "Renamed"), assertIs<AuthState.Success>(service.authState.value).userInfo)
        assertEquals(signedIn, service.sessionStart.value)
    }

    @Test
    fun `a logout keeps the last start and the next sign-in replaces it`() = runTest(dispatcher) {
        val service = AuthService(TokenAuthRepository(accessToken = "stored"))
        advanceUntilIdle()
        val restored = service.sessionStart.value

        service.clearLocalSession()
        assertEquals(restored, service.sessionStart.value)
        service.login()

        assertEquals(true, service.sessionStart.value?.interactive)
    }
}

/** A token store and nothing else: a login stores a token, a refresh keeps it, clearing the session drops it. */
private class TokenAuthRepository(private var accessToken: String?) : AuthRepository {
    var currentUser: UserInfo? = null

    override suspend fun login() {
        accessToken = "signed-in"
    }

    override suspend fun refreshTokensIfNeeded(): Boolean = accessToken != null

    override suspend fun logout() = clearLocalSession()

    override suspend fun captureLogoutTokenMaterial() = LogoutTokenMaterial(refreshToken = null, idToken = null)

    override suspend fun clearLocalSession() {
        accessToken = null
    }

    override suspend fun attemptRemoteLogout(tokenMaterial: LogoutTokenMaterial, mode: RemoteLogoutMode): List<RemoteLogoutFailure> =
        emptyList()

    override suspend fun getAccessToken(): String? = accessToken

    override suspend fun isLoggedIn(): Boolean = accessToken != null

    override suspend fun getCurrentUser(): UserInfo? = currentUser

    override suspend fun getAuthHeaders(): Map<String, String> =
        accessToken?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()
}
