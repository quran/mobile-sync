package com.quran.shared.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quran.shared.auth.di.AuthConfigFactory
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.ui.model.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import com.quran.shared.auth.utils.CommonStateFlow
import com.quran.shared.auth.utils.toCommonStateFlow

/**
 * ViewModel for authentication UI.
 *
 * Coordinates UI state by delegating business logic to [AuthRepository].
 * Platform-agnostic implementation shared across Android and iOS.
 */
class AuthViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    // Default constructor using manual DI for convenience
    constructor() : this(AuthConfigFactory.authRepository)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: CommonStateFlow<AuthState> = _authState.toCommonStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: CommonStateFlow<String?> = _error.toCommonStateFlow()

    /**
     * Initiates the OAuth login flow.
     */
    fun login() {
        viewModelScope.launch {
            try {
                _authState.value = AuthState.Loading
                _error.value = null

                val authUrl = authRepository.startLoginFlow()
                
                _authState.update {
                    AuthState.StartAuthFlow(authUrl)
                }
            } catch (e: Exception) {
                handleError(e, "Login initialization failed")
            }
        }
    }

    /**
     * Processes the OAuth redirect callback with the authorization code.
     */
    fun handleOAuthRedirect(redirectUri: String) {
        viewModelScope.launch {
            try {
                _authState.value = AuthState.Loading
                _error.value = null

                authRepository.handleRedirect(redirectUri)

                _authState.value = AuthState.Success
            } catch (e: Exception) {
                handleError(e, "Authentication failed")
            }
        }
    }

    /**
     * Refreshes access tokens if necessary.
     */
    suspend fun refreshAccessTokenIfNeeded(): Boolean {
        return authRepository.refreshTokensIfNeeded()
    }

    /**
     * Revokes tokens and signs out the user.
     */
    fun logout() {
        viewModelScope.launch {
            try {
                authRepository.logout()
                _authState.value = AuthState.Idle
                _error.value = null
            } catch (e: Exception) {
                _error.value = "Logout failed: ${e.message}"
            }
        }
    }

    /**
     * Bridge method to check authentication status.
     */
    fun isLoggedIn(): Boolean = authRepository.isLoggedIn()

    /**
     * Bridge method to get current access token.
     */
    fun getAccessToken(): String? = authRepository.getAccessToken()

    /**
     * Resets the error state.
     */
    fun clearError() {
        _error.value = null
        if (_authState.value is AuthState.Error) {
            _authState.value = AuthState.Idle
        }
    }

    private fun handleError(e: Exception, defaultMessage: String) {
        val errorMessage = e.message ?: defaultMessage
        _error.value = errorMessage
        _authState.value = AuthState.Error(e)
        // Log error for debugging
        println("Auth Error: $errorMessage")
    }
}