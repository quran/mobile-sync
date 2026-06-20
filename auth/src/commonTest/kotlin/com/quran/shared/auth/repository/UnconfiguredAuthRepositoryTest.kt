package com.quran.shared.auth.repository

import com.quran.shared.auth.di.AuthModule
import com.quran.shared.auth.model.AuthRuntimeConfig
import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.persistence.AuthStorage
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.tokenstore.TokenStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnconfiguredAuthRepositoryTest {
    @Test
    fun `auth module provides unconfigured repository without oidc metadata`() = runTest {
        val repository = AuthModule.provideAuthRepository(
            runtimeConfig = AuthRuntimeConfig.Unconfigured,
            authStorage = authStorage(UnconfiguredRecordingTokenStore()),
            json = Json { ignoreUnknownKeys = true }
        )

        assertTrue(repository is UnconfiguredAuthRepository)
        assertEquals(emptyMap(), repository.getAuthHeaders())
    }

    @Test
    fun `unconfigured auth exposes no authenticated session`() = runTest {
        val repository = UnconfiguredAuthRepository(authStorage = authStorage(UnconfiguredRecordingTokenStore()))

        assertFailsWith<AuthNotConfiguredException> {
            repository.login()
        }
        assertFailsWith<AuthNotConfiguredException> {
            repository.loginWithReauthentication()
        }
        assertFalse(repository.refreshTokensIfNeeded())
        assertFalse(repository.isLoggedIn())
        assertEquals(emptyMap(), repository.getAuthHeaders())
        assertNull(repository.getAccessToken())
        assertNull(repository.getCurrentUser())
        assertEquals(
            LogoutTokenMaterial(refreshToken = null, idToken = null),
            repository.captureLogoutTokenMaterial()
        )
        assertEquals(
            emptyList(),
            repository.attemptRemoteLogout(LogoutTokenMaterial(refreshToken = "refresh", idToken = "id"))
        )
    }

    @Test
    fun `unconfigured auth clears stale stored token data`() = runTest {
        val tokenStore = UnconfiguredRecordingTokenStore()
        val storage = authStorage(tokenStore)
        storage.storeNewSessionTokens(
            TokenResponse(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                idToken = "id-token",
                expiresIn = 3600,
                tokenType = "Bearer",
                scope = "openid"
            )
        )

        UnconfiguredAuthRepository(storage).clearLocalSession()

        assertNull(tokenStore.accessToken)
        assertNull(tokenStore.refreshToken)
        assertNull(tokenStore.idToken)
        assertNull(storage.retrieveStoredScope())
        assertNull(storage.retrieveCommittedTokenGeneration())
    }

    @Test
    fun `unconfigured startup refresh clears stale stored token data`() = runTest {
        val tokenStore = UnconfiguredRecordingTokenStore()
        val storage = authStorage(tokenStore)
        storage.storeNewSessionTokens(
            TokenResponse(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                idToken = "id-token",
                expiresIn = 3600,
                tokenType = "Bearer",
                scope = "openid"
            )
        )

        assertFalse(UnconfiguredAuthRepository(storage).refreshTokensIfNeeded())

        assertNull(tokenStore.accessToken)
        assertNull(tokenStore.refreshToken)
        assertNull(tokenStore.idToken)
        assertNull(storage.retrieveStoredScope())
        assertNull(storage.retrieveCommittedTokenGeneration())
    }

    private fun authStorage(tokenStore: UnconfiguredRecordingTokenStore): AuthStorage =
        AuthStorage(
            tokenStore = tokenStore,
            settings = MapSettings().toSuspendSettings(),
            json = Json { ignoreUnknownKeys = true }
        )
}

private class UnconfiguredRecordingTokenStore : TokenStore() {
    private val accessTokenState = MutableStateFlow<String?>(null)
    private val refreshTokenState = MutableStateFlow<String?>(null)
    private val idTokenState = MutableStateFlow<String?>(null)

    var accessToken: String? = null
        private set
    var refreshToken: String? = null
        private set
    var idToken: String? = null
        private set

    override val accessTokenFlow: StateFlow<String?> = accessTokenState
    override val refreshTokenFlow: StateFlow<String?> = refreshTokenState
    override val idTokenFlow: StateFlow<String?> = idTokenState

    override suspend fun getAccessToken(): String? = accessToken

    override suspend fun getRefreshToken(): String? = refreshToken

    override suspend fun getIdToken(): String? = idToken

    override suspend fun removeAccessToken() {
        accessToken = null
        accessTokenState.value = null
    }

    override suspend fun removeRefreshToken() {
        refreshToken = null
        refreshTokenState.value = null
    }

    override suspend fun removeIdToken() {
        idToken = null
        idTokenState.value = null
    }

    override suspend fun saveTokens(accessToken: String, refreshToken: String?, idToken: String?) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        this.idToken = idToken
        accessTokenState.value = accessToken
        refreshTokenState.value = refreshToken
        idTokenState.value = idToken
    }
}
