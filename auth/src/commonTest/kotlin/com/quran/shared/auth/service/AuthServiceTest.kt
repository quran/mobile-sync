package com.quran.shared.auth.service

import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.repository.AuthRepository
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
}

private class RecordingAuthRepository(
    private var accessToken: String?,
    private val loginAccessToken: String? = accessToken,
    private val currentUser: UserInfo?
) : AuthRepository {
    override suspend fun login() {
        accessToken = loginAccessToken
    }

    override suspend fun loginWithReauthentication() = Unit

    override suspend fun refreshTokensIfNeeded(): Boolean = accessToken != null

    override suspend fun logout() {
        accessToken = null
    }

    override suspend fun getAccessToken(): String? = accessToken

    override suspend fun isLoggedIn(): Boolean = accessToken != null

    override suspend fun getCurrentUser(): UserInfo? = currentUser

    override suspend fun getAuthHeaders(): Map<String, String> {
        val token = accessToken ?: return emptyMap()
        return mapOf("Authorization" to "Bearer $token")
    }
}
