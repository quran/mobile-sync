package com.quran.shared.demo.android.ui.auth

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.quran.shared.auth.AuthenticationManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random
import androidx.core.content.edit

/**
 * ViewModel that manages authentication state and OAuth flow for Android.
 *
 * Implements OAuth 2.0 with PKCE (RFC 7636) for secure mobile authentication.
 * Handles:
 * - PKCE code generation (code_verifier and code_challenge)
 * - Browser launch for user authorization
 * - OAuth redirect callback processing
 * - Token storage in encrypted SharedPreferences
 * - Token refresh and validation
 *
 * Follows CLAUDE.md architecture by separating UI state (ViewModel) from
 * business logic (AuthenticationManager).
 */
class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val authManager = AuthenticationManager()

    // TODO: check if to use shared storage instead of SharedPrefs
    private val prefs: SharedPreferences = application.getSharedPreferences("oauth", Context.MODE_PRIVATE)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error


    /**
     * Initiates OAuth login flow.
     *
     * Process:
     * 1. Generate PKCE code verifier (cryptographically random 128 characters)
     * 2. Create code challenge (SHA-256 hash + base64 encoding)
     * 3. Generate state parameter (CSRF protection)
     * 4. Store verifier and state for later validation
     * 5. Build authorization URL
     * 6. Launch system browser
     *
     * @param activity Activity to launch browser from
     */
    fun login(activity: Activity) {
        viewModelScope.launch {
            try {
                _authState.value = AuthState.Loading
                _error.value = null

                // Generate PKCE code verifier (43-128 characters, unreserved characters)
                val codeVerifier = generateCodeVerifier()

                // Generate state for CSRF protection
                val state = generateRandomState()

                // Build authorization URL with PKCE
                val authUrl = authManager.buildAuthorizationUrl(
                    codeVerifier = codeVerifier,
                    state = state
                )
                // TODO: remove logging from production
                println("DEBUG: Authorization URL: $authUrl")

                // Store verifier and state for callback
                storeOAuthState(codeVerifier, state)

                // Launch browser with authorization URL
                launchBrowser(activity, authUrl)

            } catch (e: Exception) {
                _error.value = e.message ?: "Authentication failed"
                _authState.value = AuthState.Error(e)
                // TODO: remove logging from production
                println("DEBUG: OAuth error: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Handles OAuth redirect callback from system browser.
     *
     * Called from MainActivity when app is opened via deep link:
     * com.quran.oauth://callback?code=AUTH_CODE&state=STATE_VALUE
     *
     * Process:
     * 1. Parse redirect URI
     * 2. Check for error in redirect
     * 3. Validate state parameter (CSRF protection)
     * 4. Extract authorization code
     * 5. Exchange code for tokens using AuthenticationManager
     * 6. Store tokens securely
     * 7. Update UI state
     *
     * @param redirectUri The redirect URI from OAuth provider
     */
    fun handleOAuthRedirect(redirectUri: String) {
        viewModelScope.launch {
            try {
                _authState.value = AuthState.Loading
                _error.value = null

                // Parse redirect URI
                val uri = Uri.parse(redirectUri)

                // Check for error in redirect
                val errorParam = uri.getQueryParameter("error")
                if (errorParam != null) {
                    val errorDescription = uri.getQueryParameter("error_description")
                        ?: "Unknown error"
                    throw Exception("OAuth Error: $errorParam - $errorDescription")
                }

                // Extract authorization code and state
                val authCode = uri.getQueryParameter("code")
                    ?: throw Exception("No authorization code in redirect")

                val returnedState = uri.getQueryParameter("state")
                    ?: throw Exception("No state parameter in redirect")

                // Validate state (CSRF protection)
                val storedState = retrieveStoredState()
                if (storedState != returnedState) {
                    throw Exception("State parameter mismatch - possible CSRF attack")
                }

                // Retrieve stored code verifier
                val codeVerifier = retrieveStoredCodeVerifier()
                    ?: throw Exception("Code verifier not found - invalid state")

                // Exchange authorization code for tokens
                val tokenResponse = authManager.exchangeCodeForToken(
                    code = authCode,
                    codeVerifier = codeVerifier
                )

                // Store tokens securely
                storeTokens(tokenResponse)

                // Clear stored OAuth state
                clearOAuthState()

                _authState.value = AuthState.Success

            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to handle OAuth redirect"
                _authState.value = AuthState.Error(e)
            }
        }
    }

    /**
     * Refreshes the access token if expired.
     *
     * Should be called before making API requests to ensure valid token.
     *
     * @return true if refresh succeeded, false otherwise
     */
    suspend fun refreshAccessTokenIfNeeded(): Boolean {
        return try {
            val refreshToken = retrieveStoredRefreshToken() ?: return false
            val expirationTime = retrieveTokenExpiration()

            // Check if token is expired (with 60 second buffer)
            if (!authManager.isTokenValid(expirationTime)) {
                val newTokenResponse = authManager.refreshToken(refreshToken)
                storeTokens(newTokenResponse)
                true
            } else {
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Logs out user by revoking tokens and clearing storage.
     */
    fun logout() {
        viewModelScope.launch {
            try {
                val accessToken = retrieveStoredAccessToken()
                if (accessToken != null) {
                    authManager.revokeToken(accessToken, "access_token")
                }

                val refreshToken = retrieveStoredRefreshToken()
                if (refreshToken != null) {
                    authManager.revokeToken(refreshToken, "refresh_token")
                }

                clearAllTokens()
                _authState.value = AuthState.Idle
                _error.value = null

            } catch (e: Exception) {
                _error.value = "Logout failed: ${e.message}"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    // ========================= Helper Methods =========================

    /**
     * Generates a cryptographically random PKCE code verifier.
     *
     * RFC 7636: 43-128 characters from unreserved characters
     * [A-Z] [a-z] [0-9] - . _ ~
     */
    private fun generateCodeVerifier(): String {
        val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        return (1..128).map { charset[Random.nextInt(charset.length)] }.joinToString("")
    }

    /**
     * Generates a random state parameter for CSRF protection.
     */
    private fun generateRandomState(): String {
        val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..32).map { charset[Random.nextInt(charset.length)] }.joinToString("")
    }

    /**
     * Launches system browser with OAuth authorization URL.
     */
    private fun launchBrowser(activity: Activity, authUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
        activity.startActivity(intent)
    }

    /**
     * Stores OAuth state (code_verifier, state) for callback validation.
     */
    private fun storeOAuthState(codeVerifier: String, state: String) {
        prefs.edit {
            putString("code_verifier", codeVerifier)
                .putString("state", state)
        }
    }

    /**
     * Clears OAuth state after successful authentication.
     */
    private fun clearOAuthState() {
        prefs.edit {
            remove("code_verifier")
                .remove("state")
        }
    }

    /**
     * Stores tokens securely in SharedPreferences.
     *
     * In production, use EncryptedSharedPreferences or Android Keystore.
     */
    private fun storeTokens(tokenResponse: com.quran.shared.auth.TokenResponse) {
        val expirationTime = System.currentTimeMillis() + (tokenResponse.expiresIn * 1000)
        prefs.edit {
            putString("access_token", tokenResponse.accessToken)
                .putString("refresh_token", tokenResponse.refreshToken)
                .putLong("token_expiration", expirationTime)
                .putLong("token_retrieved_at", System.currentTimeMillis())
        }
    }

    /**
     * Retrieves stored access token.
     */
    fun retrieveStoredAccessToken(): String? {
        return prefs.getString("access_token", null)
    }

    /**
     * Retrieves stored refresh token.
     */
    private fun retrieveStoredRefreshToken(): String? {
        return prefs.getString("refresh_token", null)
    }

    /**
     * Retrieves stored code verifier for token exchange.
     */
    private fun retrieveStoredCodeVerifier(): String? {
        return prefs.getString("code_verifier", null)
    }

    /**
     * Retrieves stored state parameter for validation.
     */
    private fun retrieveStoredState(): String? {
        return prefs.getString("state", null)
    }

    /**
     * Retrieves token expiration time.
     */
    private fun retrieveTokenExpiration(): Long {
        return prefs.getLong("token_expiration", 0)
    }

    /**
     * Clears all stored tokens.
     */
    private fun clearAllTokens() {
        prefs.edit {
            remove("access_token")
                .remove("refresh_token")
                .remove("token_expiration")
                .remove("token_retrieved_at")
                .remove("code_verifier")
                .remove("state")
        }
    }
}

/**
 * Sealed class representing authentication state
 */
sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    object Success : AuthState()
    data class Error(val exception: Exception) : AuthState()
}

