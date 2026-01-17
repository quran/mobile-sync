package com.quran.shared.auth.ui

import androidx.lifecycle.viewModelScope
import com.quran.shared.auth.AuthenticationManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import com.quran.shared.auth.di.AuthConfigFactory
import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.auth.ui.model.AuthState
import io.ktor.http.parseUrl
import kotlinx.coroutines.flow.update

class AuthViewModel(private val authStorage: AuthStorage) : ViewModel() {
    
    // Default constructor for easier instantiation from iOS/Android when DI is not used
    constructor() : this(AuthStorage())

    // Instantiate the manager, later should be injected
    private val authManager = AuthenticationManager(AuthConfigFactory.createDefault())

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
     */
    fun login() {
        viewModelScope.launch {
            try {
                _authState.value = AuthState.Loading
                _error.value = null

                // Generate PKCE code verifier (43-128 characters, unreserved characters)
                val codeVerifier = authStorage.generateCodeVerifier()

                // Generate state for CSRF protection
                val state = authStorage.generateRandomState()

                // Build authorization URL with PKCE
                val authUrl = authManager.buildAuthorizationUrl(
                    codeVerifier = codeVerifier,
                    state = state
                )
                // TODO: remove logging from production
                println("DEBUG: Authorization URL: $authUrl")

                // Store verifier and state for callback
                authStorage.storeOAuthState(codeVerifier, state)

                // Launch browser with authorization URL
                _authState.update {
                    AuthState.StartAuthFlow(authUrl)
                }

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
                val uri = parseUrl(redirectUri)

                // Check for error in redirect
                uri?.parameters["error"]?.let { errorParam ->
                    val errorDescription = uri.parameters["error_description"]
                        ?: "Unknown error"
                    throw Exception("OAuth Error: $errorParam - $errorDescription")
                }

                // Extract authorization code and state
                val authCode = uri?.parameters["code"]
                    ?: throw Exception("No authorization code in redirect")

                val returnedState = uri.parameters["state"]
                    ?: throw Exception("No state parameter in redirect")

                // Validate state (CSRF protection)
                val storedState = authStorage.retrieveStoredState()
                if (storedState != returnedState) {
                    throw Exception("State parameter mismatch - possible CSRF attack")
                }

                // Retrieve stored code verifier
                val codeVerifier = authStorage.retrieveStoredCodeVerifier()
                    ?: throw Exception("Code verifier not found - invalid state")

                // Exchange authorization code for tokens
                val tokenResponse = authManager.exchangeCodeForToken(
                    code = authCode,
                    codeVerifier = codeVerifier
                )

                // Store tokens securely
                authStorage.storeTokens(tokenResponse)

                // Clear stored OAuth state
                authStorage.clearOAuthState()

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
            val refreshToken = authStorage.retrieveStoredRefreshToken() ?: return false
            val expirationTime = authStorage.retrieveTokenExpiration()

            // Check if token is expired (with 60 second buffer)
            if (!authManager.isTokenValid(expirationTime)) {
                val newTokenResponse = authManager.refreshToken(refreshToken)
                authStorage.storeTokens(newTokenResponse)
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
                val accessToken = authStorage.retrieveStoredAccessToken()
                if (accessToken != null) {
                    authManager.revokeToken(accessToken, "access_token")
                }

                val refreshToken = authStorage.retrieveStoredRefreshToken()
                if (refreshToken != null) {
                    authManager.revokeToken(refreshToken, "refresh_token")
                }

                authStorage.clearAllTokens()
                _authState.value = AuthState.Idle
                _error.value = null

            } catch (e: Exception) {
                _error.value = "Logout failed: ${e.message}"
            }
        }
    }

    fun getAccessToken(): String? {
        return authStorage.retrieveStoredAccessToken()
    }

    fun isLoggedIn(): Boolean {
        return authStorage.retrieveStoredAccessToken() != null
    }

    fun clearError() {
        _error.value = null
    }

}