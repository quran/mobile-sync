package com.quran.shared.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quran.shared.auth.di.AuthConfigFactory
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.OidcAuthRepository
import com.quran.shared.auth.ui.model.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

import com.quran.shared.auth.utils.CommonStateFlow
import com.quran.shared.auth.utils.toCommonStateFlow

/**
 * ViewModel for authentication UI.
 *
 * Coordinates UI state by delegating to the OIDC library's CodeAuthFlow via [AuthRepository].
 * Platform-agnostic implementation shared across Android and iOS.
 * 
 * Prerequisites:
 * - AuthFlowFactoryProvider must be initialized before calling login()
 * - On Android: Initialize in MainActivity with AndroidCodeAuthFlowFactory
 * - On iOS: Initialize at app startup with IosCodeAuthFlowFactory
 */
class AuthViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    // Default constructor using manual DI
    constructor() : this(AuthConfigFactory.authRepository)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: CommonStateFlow<AuthState> = _authState.toCommonStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: CommonStateFlow<String?> = _error.toCommonStateFlow()

    init {
        checkCurrentSession()
        checkPendingLogin()
    }

    private fun checkCurrentSession() {
        if (isLoggedIn()) {
            val user = authRepository.getCurrentUser()
            if (user != null) {
                _authState.value = AuthState.Success(user)
            }
        }
    }

    private fun checkPendingLogin() {
        // Check if there's a pending login that needs to be continued
        // (e.g., app was killed during browser auth)
        viewModelScope.launch {
            try {
                val oidcRepo = authRepository as? OidcAuthRepository
                if (oidcRepo?.canContinueLogin() == true) {
                    _authState.value = AuthState.Loading
                    oidcRepo.continueLogin()
                    
                    val user = authRepository.getCurrentUser()
                    if (user != null) {
                        _authState.value = AuthState.Success(user)
                    }
                }
            } catch (e: Exception) {
                // Ignore - no pending login
            }
        }
    }

    /**
     * Initiates the OAuth login flow.
     * This will launch the system browser for authentication.
     */
    fun login() {
        viewModelScope.launch {
            try {
                _authState.value = AuthState.Loading
                _error.value = null

                // This launches browser and waits for complete flow
                authRepository.login()

                val user = authRepository.getCurrentUser()
                if (user != null) {
                    _authState.value = AuthState.Success(user)
                } else {
                    throw Exception("Failed to retrieve user info after login")
                }
            } catch (e: Exception) {
                handleError(e, "Login failed")
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
     * Check if user is currently logged in.
     */
    fun isLoggedIn(): Boolean = authRepository.isLoggedIn()

    /**
     * Get current access token.
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
        println("Auth Error: $errorMessage")
    }
}