package com.quran.shared.auth.repository

import com.quran.shared.auth.di.AuthModule
import com.quran.shared.auth.model.AuthRuntimeConfig
import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.persistence.AuthStorage
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.tokenstore.TokenStore
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse
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
            repository.attemptRemoteLogout(
                LogoutTokenMaterial(refreshToken = "refresh", idToken = "id"),
                RemoteLogoutMode.TOKEN_REVOCATION_ONLY
            )
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

@Suppress("OVERRIDE_DEPRECATION")
private class UnconfiguredRecordingTokenStore : TokenStore() {
    private val tokenResponseState = MutableStateFlow<AccessTokenResponse?>(null)

    val accessToken: String? get() = tokenResponseState.value?.access_token
    val refreshToken: String? get() = tokenResponseState.value?.refresh_token
    val idToken: String? get() = tokenResponseState.value?.id_token

    override val accessTokenFlow = tokenResponseState.map { it?.access_token }
    override val refreshTokenFlow = tokenResponseState.map { it?.refresh_token }
    override val idTokenFlow = tokenResponseState.map { it?.id_token }
    override val tokenResponseFlow: StateFlow<AccessTokenResponse?> = tokenResponseState

    override suspend fun getAccessToken(): String? = accessToken

    override suspend fun getRefreshToken(): String? = refreshToken

    override suspend fun getIdToken(): String? = idToken

    override suspend fun getTokenResponse(): AccessTokenResponse? = tokenResponseState.value

    override suspend fun removeTokens() {
        tokenResponseState.value = null
    }

    override suspend fun saveTokens(tokens: AccessTokenResponse) {
        tokenResponseState.value = tokens
    }
}
