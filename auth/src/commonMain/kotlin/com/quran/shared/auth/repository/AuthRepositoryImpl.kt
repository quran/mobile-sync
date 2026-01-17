package com.quran.shared.auth.repository

import com.quran.shared.auth.AuthenticationManager
import com.quran.shared.auth.persistence.AuthStorage
import io.ktor.http.parseUrl

class AuthRepositoryImpl(
    private val authManager: AuthenticationManager,
    private val authStorage: AuthStorage
) : AuthRepository {

    override fun startLoginFlow(): String {
        // Generate PKCE state
        val codeVerifier = authStorage.generateCodeVerifier()
        val state = authStorage.generateRandomState()

        // Store for later validation during redirect
        authStorage.storeOAuthState(codeVerifier, state)

        // Build the URL
        return authManager.buildAuthorizationUrl(
            codeVerifier = codeVerifier,
            state = state
        )
    }

    override suspend fun handleRedirect(redirectUri: String) {
        val uri = parseUrl(redirectUri) ?: throw Exception("Invalid redirect URI")

        // 1. Check for provider error
        uri.parameters["error"]?.let { errorParam ->
            val errorDescription = uri.parameters["error_description"] ?: "Unknown error"
            throw Exception("OAuth Error: $errorParam - $errorDescription")
        }

        // 2. Extract code and state
        val authCode = uri.parameters["code"] ?: throw Exception("No authorization code in redirect")
        val returnedState = uri.parameters["state"] ?: throw Exception("No state parameter in redirect")

        // 3. Validate state (CSRF protection)
        val storedState = authStorage.retrieveStoredState()
        if (storedState != returnedState) {
            throw Exception("State parameter mismatch - possible CSRF attack")
        }

        // 4. Retrieve verifier and exchange code for tokens
        val codeVerifier = authStorage.retrieveStoredCodeVerifier()
            ?: throw Exception("Code verifier not found - invalid state")

        val tokenResponse = authManager.exchangeCodeForToken(
            code = authCode,
            codeVerifier = codeVerifier
        )

        // 5. Persist tokens and cleanup state
        authStorage.storeTokens(tokenResponse)
        authStorage.clearOAuthState()
    }

    override suspend fun refreshTokensIfNeeded(): Boolean {
        return try {
            val refreshToken = authStorage.retrieveStoredRefreshToken() ?: return false
            val expirationTime = authStorage.retrieveTokenExpiration()

            if (!authManager.isTokenValid(expirationTime)) {
                val newTokenResponse = authManager.refreshToken(refreshToken)
                authStorage.storeTokens(newTokenResponse)
            }
            true
        } catch (e: Exception) {
            // If refresh fails, tokens might be revoked or invalid
            false
        }
    }

    override suspend fun logout() {
        try {
            val accessToken = authStorage.retrieveStoredAccessToken()
            if (accessToken != null) {
                authManager.revokeToken(accessToken, "access_token")
            }

            val refreshToken = authStorage.retrieveStoredRefreshToken()
            if (refreshToken != null) {
                authManager.revokeToken(refreshToken, "refresh_token")
            }
        } finally {
            // Always clear storage even if network calls fail
            authStorage.clearAllTokens()
        }
    }

    override fun getAccessToken(): String? = authStorage.retrieveStoredAccessToken()

    override fun isLoggedIn(): Boolean = authStorage.retrieveStoredAccessToken() != null

    override fun getStoredState(): String? = authStorage.retrieveStoredState()
}
