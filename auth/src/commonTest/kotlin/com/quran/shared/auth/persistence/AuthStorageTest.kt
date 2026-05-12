package com.quran.shared.auth.persistence

import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.model.UserInfo
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.tokenstore.TokenStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthStorageTest {

    @Test
    fun `storeTokens persists OAuth tokens through TokenStore`() = runTest {
        val tokenStore = RecordingTokenStore()
        val storage = authStorage(tokenStore)

        storage.storeTokens(
            TokenResponse(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                idToken = "id-token",
                expiresIn = 3600,
                tokenType = "Bearer",
                scope = "openid profile"
            )
        )

        assertEquals("access-token", tokenStore.accessToken)
        assertEquals("refresh-token", tokenStore.refreshToken)
        assertEquals("id-token", tokenStore.idToken)
        assertEquals("openid profile", storage.retrieveStoredScope())
        assertEquals("access-token", storage.retrieveStoredAccessToken())
        assertEquals("refresh-token", storage.retrieveStoredRefreshToken())
        assertEquals("id-token", storage.retrieveStoredIdToken())
    }

    @Test
    fun `storeTokens preserves refresh token when refresh response omits one`() = runTest {
        val tokenStore = RecordingTokenStore(
            accessToken = "old-access-token",
            refreshToken = "stable-refresh-token",
            idToken = "old-id-token"
        )
        val storage = authStorage(tokenStore)

        storage.storeTokens(
            TokenResponse(
                accessToken = "new-access-token",
                refreshToken = null,
                idToken = null,
                expiresIn = 3600,
                tokenType = "Bearer"
            )
        )

        assertEquals("new-access-token", tokenStore.accessToken)
        assertEquals("stable-refresh-token", tokenStore.refreshToken)
        assertEquals("old-id-token", tokenStore.idToken)
    }

    @Test
    fun `storeNewSessionTokens clears stale identity metadata and does not preserve old tokens`() = runTest {
        val tokenStore = RecordingTokenStore(
            accessToken = "old-access-token",
            refreshToken = "old-refresh-token",
            idToken = "old-id-token"
        )
        val storage = authStorage(tokenStore)
        storage.storeUserInfo(UserInfo(id = "old-user", name = "Old User"))

        storage.storeNewSessionTokens(
            TokenResponse(
                accessToken = "new-access-token",
                refreshToken = null,
                idToken = null,
                expiresIn = 3600,
                tokenType = "Bearer"
            )
        )

        assertEquals("new-access-token", tokenStore.accessToken)
        assertNull(tokenStore.refreshToken)
        assertNull(tokenStore.idToken)
        assertNull(storage.retrieveUserInfo())
    }

    @Test
    fun `clearAllTokens clears token store and auth metadata`() = runTest {
        val tokenStore = RecordingTokenStore(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            idToken = "id-token"
        )
        val storage = authStorage(tokenStore)

        storage.storeTokens(
            TokenResponse(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                idToken = "id-token",
                expiresIn = 3600,
                tokenType = "Bearer",
                scope = "openid profile"
            )
        )

        storage.clearAllTokens()

        assertNull(tokenStore.accessToken)
        assertNull(tokenStore.refreshToken)
        assertNull(tokenStore.idToken)
        assertNull(storage.retrieveStoredScope())
        assertEquals(0, storage.retrieveTokenExpiration())
    }

    private fun authStorage(tokenStore: RecordingTokenStore): AuthStorage {
        return AuthStorage(
            tokenStore = tokenStore,
            settings = MapSettings().toSuspendSettings(),
            json = Json { ignoreUnknownKeys = true }
        )
    }
}

private class RecordingTokenStore(
    accessToken: String? = null,
    refreshToken: String? = null,
    idToken: String? = null
) : TokenStore() {
    private val accessTokenState = MutableStateFlow(accessToken)
    private val refreshTokenState = MutableStateFlow(refreshToken)
    private val idTokenState = MutableStateFlow(idToken)

    var accessToken: String? = accessToken
        private set
    var refreshToken: String? = refreshToken
        private set
    var idToken: String? = idToken
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
