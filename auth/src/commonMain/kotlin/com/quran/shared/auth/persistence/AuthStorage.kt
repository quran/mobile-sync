package com.quran.shared.auth.persistence

import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.utils.currentTimeMillis
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set

/**
 * Persists authentication tokens and OAuth state across app restarts.
 *
 * Uses Multiplatform Settings (com.russhwolf:multiplatform-settings) for
 * platform-agnostic key-value storage:
 * - Android: SharedPreferences
 * - iOS: NSUserDefaults
 */
class AuthStorage(private val settings: Settings = Settings()) {

    /**
     * Retrieves stored access token.
     */
    fun retrieveStoredAccessToken(): String? {
        return settings.getStringOrNull(KEY_ACCESS_TOKEN)
    }

    /**
     * Retrieves stored refresh token.
     */
    fun retrieveStoredRefreshToken(): String? {
        return settings.getStringOrNull(KEY_REFRESH_TOKEN)
    }

    /**
     * Retrieves stored code verifier for token exchange.
     */
    fun retrieveStoredCodeVerifier(): String? {
        return settings.getStringOrNull(KEY_CODE_VERIFIER)
    }

    /**
     * Retrieves stored state parameter for validation.
     */
    fun retrieveStoredState(): String? {
        return settings.getStringOrNull(KEY_STATE)
    }

    /**
     * Retrieves token expiration time.
     */
    fun retrieveTokenExpiration(): Long {
        return settings.getLong(KEY_TOKEN_EXPIRATION, 0)
    }

    /**
     * Retrieves stored user info.
     */
    fun retrieveUserInfo(): String? {
        return settings.getStringOrNull(KEY_USER_INFO)
    }

    /**
     * Retrieves stored id token (JWT).
     */
    fun retrieveStoredIdToken(): String? {
        return settings.getStringOrNull(KEY_ID_TOKEN)
    }

    /**
     * Clears all stored tokens.
     */
    fun clearAllTokens() {
        settings.remove(KEY_ACCESS_TOKEN)
        settings.remove(KEY_REFRESH_TOKEN)
        settings.remove(KEY_ID_TOKEN)
        settings.remove(KEY_USER_INFO)
        settings.remove(KEY_TOKEN_EXPIRATION)
        settings.remove(KEY_TOKEN_RETRIEVED_AT)
        settings.remove(KEY_CODE_VERIFIER)
        settings.remove(KEY_STATE)
    }

    /**
     * Stores token response and calculates expiration timestamp.
     */
    fun storeTokens(tokenResponse: TokenResponse) {
        val expirationTime = currentTimeMillis() + (tokenResponse.expiresIn * 1000)
        settings[KEY_ACCESS_TOKEN] = tokenResponse.accessToken
        settings[KEY_REFRESH_TOKEN] = tokenResponse.refreshToken
        settings[KEY_ID_TOKEN] = tokenResponse.idToken
        settings[KEY_TOKEN_EXPIRATION] = expirationTime
        settings[KEY_TOKEN_RETRIEVED_AT] = currentTimeMillis()
    }

    /**
     * Stores user info as JSON string.
     */
    fun storeUserInfo(userInfoJson: String) {
        settings[KEY_USER_INFO] = userInfoJson
    }

    /**
     * Stores OAuth state (verifier and state) during login flow initiation.
     */
    fun storeOAuthState(codeVerifier: String, state: String) {
        settings[KEY_CODE_VERIFIER] = codeVerifier
        settings[KEY_STATE] = state
    }

    /**
     * Clears OAuth state after successful or failed callback.
     */
    fun clearOAuthState() {
        settings.remove(KEY_CODE_VERIFIER)
        settings.remove(KEY_STATE)
    }

    /**
     * Delegates to AuthUtils to generate a code verifier.
     */
    fun generateCodeVerifier(): String = com.quran.shared.auth.utils.generateCodeVerifier()

    /**
     * Delegates to AuthUtils to generate a random state.
     */
    fun generateRandomState(): String = com.quran.shared.auth.utils.generateRandomState()

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_ID_TOKEN = "id_token"
        private const val KEY_TOKEN_EXPIRATION = "token_expiration"
        private const val KEY_TOKEN_RETRIEVED_AT = "token_retrieved_at"
        private const val KEY_CODE_VERIFIER = "code_verifier"
        private const val KEY_STATE = "state"
        private const val KEY_USER_INFO = "user_info"
    }
}