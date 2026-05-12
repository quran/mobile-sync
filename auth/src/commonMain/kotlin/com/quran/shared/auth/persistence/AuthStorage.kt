package com.quran.shared.auth.persistence

import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.utils.currentTimeMillis
import com.quran.shared.di.AppScope
import com.russhwolf.settings.coroutines.SuspendSettings
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.tokenstore.TokenStore

/**
 * Persists authentication token material and non-secret session metadata across app restarts.
 *
 * OAuth tokens are stored through [TokenStore] so each platform can use its secure storage
 * implementation. Metadata such as expiry, scope, and cached profile data remains outside the
 * token store because the OIDC token-store API intentionally owns only token material.
 */
@SingleIn(AppScope::class)
class AuthStorage @Inject constructor(
    private val tokenStore: TokenStore,
    private val settings: SuspendSettings,
    private val json: Json
) {

    suspend fun retrieveStoredAccessToken(): String? = tokenStore.getAccessToken()
    suspend fun retrieveStoredRefreshToken(): String? = tokenStore.getRefreshToken()
    suspend fun retrieveStoredIdToken(): String? = tokenStore.getIdToken()
    suspend fun retrieveStoredScope(): String? = settings.getStringOrNull(KEY_SCOPE)
    suspend fun retrieveTokenExpiration(): Long = settings.getLong(KEY_TOKEN_EXPIRATION, 0)

    /**
     * Retrieves cached user profile data from non-token session metadata.
     */
    suspend fun retrieveUserInfo(): UserInfo? {
        val userInfoJson = settings.getStringOrNull(KEY_USER_INFO) ?: return null
        return try {
            json.decodeFromString<UserInfo>(userInfoJson)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Stores token response data for an existing session while preserving stable refresh and ID
     * tokens when a refresh response omits them.
     *
     * Some providers return only a new access token during refresh. Passing null directly to
     * [TokenStore.saveTokens] would remove the previous refresh token, so this method reads the
     * existing secure token values first and keeps them unless replacements are present.
     *
     * @param tokenResponse token response returned from a refresh or same-session update.
     */
    suspend fun storeTokens(tokenResponse: TokenResponse) {
        storeTokens(tokenResponse, preserveExistingTokens = true)
    }

    /**
     * Stores token response data for a newly completed interactive login session.
     *
     * This intentionally does not preserve existing refresh or ID tokens. A successful login or
     * login-continuation is a session boundary, so stale identity metadata must be removed before
     * the new token material is stored.
     *
     * @param tokenResponse token response returned from a successful interactive login.
     */
    suspend fun storeNewSessionTokens(tokenResponse: TokenResponse) {
        clearSessionMetadata()
        storeTokens(tokenResponse, preserveExistingTokens = false)
    }

    private suspend fun storeTokens(
        tokenResponse: TokenResponse,
        preserveExistingTokens: Boolean
    ) {
        val expirationTime = if (tokenResponse.expiresAt != null) {
            try {
                Instant.parse(tokenResponse.expiresAt).toEpochMilliseconds()
            } catch (e: Exception) {
                currentTimeMillis() + (tokenResponse.expiresIn * 1000)
            }
        } else {
            currentTimeMillis() + (tokenResponse.expiresIn * 1000)
        }
        val refreshToken = tokenResponse.refreshToken
            ?: if (preserveExistingTokens) tokenStore.getRefreshToken() else null
        val idToken = tokenResponse.idToken
            ?: if (preserveExistingTokens) tokenStore.getIdToken() else null

        tokenStore.saveTokens(
            accessToken = tokenResponse.accessToken,
            refreshToken = refreshToken,
            idToken = idToken
        )
        tokenResponse.scope?.let { settings.putString(KEY_SCOPE, it) }
        settings.putLong(KEY_TOKEN_EXPIRATION, expirationTime)
        settings.putLong(KEY_TOKEN_RETRIEVED_AT, currentTimeMillis())
    }

    suspend fun storeUserInfo(userInfo: UserInfo) {
        settings.putString(KEY_USER_INFO, json.encodeToString(userInfo))
    }

    /**
     * Clears secure token material and non-token auth metadata.
     */
    suspend fun clearAllTokens() {
        tokenStore.removeAccessToken()
        tokenStore.removeRefreshToken()
        tokenStore.removeIdToken()
        clearSessionMetadata()
    }

    private suspend fun clearSessionMetadata() {
        settings.remove(KEY_SCOPE)
        settings.remove(KEY_USER_INFO)
        settings.remove(KEY_TOKEN_EXPIRATION)
        settings.remove(KEY_TOKEN_RETRIEVED_AT)
    }

    companion object {
        private const val KEY_SCOPE = "scope"
        private const val KEY_TOKEN_EXPIRATION = "token_expiration"
        private const val KEY_TOKEN_RETRIEVED_AT = "token_retrieved_at"
        private const val KEY_USER_INFO = "user_info"
    }
}
