package com.quran.shared.auth.persistence

import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.utils.currentTimeMillis
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import kotlinx.serialization.json.Json

/**
 * Persists authentication tokens and OAuth state across app restarts.
 */
class AuthStorage(
    private val settings: Settings = Settings(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    fun retrieveStoredAccessToken(): String? = settings.getStringOrNull(KEY_ACCESS_TOKEN)
    fun retrieveStoredRefreshToken(): String? = settings.getStringOrNull(KEY_REFRESH_TOKEN)
    fun retrieveStoredIdToken(): String? = settings.getStringOrNull(KEY_ID_TOKEN)
    fun retrieveTokenExpiration(): Long = settings.getLong(KEY_TOKEN_EXPIRATION, 0)
    
    /**
     * Retrieves stored code verifier for token exchange (survival after process death).
     */
    fun retrieveStoredCodeVerifier(): String? = settings.getStringOrNull(KEY_CODE_VERIFIER)

    /**
     * Retrieves stored state parameter for validation (survival after process death).
     */
    fun retrieveStoredState(): String? = settings.getStringOrNull(KEY_STATE)

    fun retrieveUserInfo(): UserInfo? {
        val userInfoJson = settings.getStringOrNull(KEY_USER_INFO) ?: return null
        return try {
            json.decodeFromString<UserInfo>(userInfoJson)
        } catch (e: Exception) {
            null
        }
    }

    fun storeTokens(tokenResponse: TokenResponse) {
        val expirationTime = currentTimeMillis() + (tokenResponse.expiresIn * 1000)
        settings[KEY_ACCESS_TOKEN] = tokenResponse.accessToken
        tokenResponse.refreshToken?.let { settings[KEY_REFRESH_TOKEN] = it }
        tokenResponse.idToken?.let { settings[KEY_ID_TOKEN] = it }
        settings[KEY_TOKEN_EXPIRATION] = expirationTime
        settings[KEY_TOKEN_RETRIEVED_AT] = currentTimeMillis()
    }

    fun storeUserInfo(userInfo: UserInfo) {
        settings[KEY_USER_INFO] = json.encodeToString(userInfo)
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

    fun clearAllTokens() {
        settings.remove(KEY_ACCESS_TOKEN)
        settings.remove(KEY_REFRESH_TOKEN)
        settings.remove(KEY_ID_TOKEN)
        settings.remove(KEY_USER_INFO)
        settings.remove(KEY_TOKEN_EXPIRATION)
        settings.remove(KEY_TOKEN_RETRIEVED_AT)
        clearOAuthState()
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_ID_TOKEN = "id_token"
        private const val KEY_TOKEN_EXPIRATION = "token_expiration"
        private const val KEY_TOKEN_RETRIEVED_AT = "token_retrieved_at"
        private const val KEY_USER_INFO = "user_info"
        private const val KEY_CODE_VERIFIER = "code_verifier"
        private const val KEY_STATE = "state"
    }
}
