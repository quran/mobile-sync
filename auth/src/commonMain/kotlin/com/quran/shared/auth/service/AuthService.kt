package com.quran.shared.auth.service

import com.quran.shared.auth.di.AuthConfigFactory
import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.OidcAuthRepository
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Shared service for authentication logic and state.
 * 
 * This service centralizes authentication operations and maintains the authentication state,
 * making it easy to share across platforms while allowing native ViewModels (iOS/Android)
 * to handle platform-specific UI concerns.
 */
class AuthService(
    private val authRepository: AuthRepository = AuthConfigFactory.authRepository
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    @NativeCoroutinesState
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        checkCurrentSession()
        checkPendingLogin()
    }

    private fun checkCurrentSession() {
        if (authRepository.isLoggedIn()) {
            val user = authRepository.getCurrentUser()
            if (user != null) {
                _authState.value = AuthState.Success(user)
            }
        }
    }

    private fun checkPendingLogin() {
        scope.launch {
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
     */
    @NativeCoroutines
    suspend fun login() {
        try {
            _authState.value = AuthState.Loading
            authRepository.login()
            val user = authRepository.getCurrentUser()
            if (user != null) {
                _authState.value = AuthState.Success(user)
            } else {
                throw Exception("Failed to retrieve user info after login")
            }
        } catch (e: Exception) {
            handleError(e, "Login failed")
            throw e
        }
    }

    /**
     * Signs out the user and clears all tokens.
     */
    @NativeCoroutines
    suspend fun logout() {
        try {
            authRepository.logout()
            _authState.value = AuthState.Idle
        } catch (e: Exception) {
            handleError(e, "Logout failed")
            throw e
        }
    }

    /**
     * Refreshes access tokens if necessary.
     */
    @NativeCoroutines
    suspend fun refreshAccessTokenIfNeeded(): Boolean {
        return authRepository.refreshTokensIfNeeded()
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
        if (_authState.value is AuthState.Error) {
            _authState.value = AuthState.Idle
        }
    }

    private fun handleError(e: Exception, defaultMessage: String) {
        val errorMessage = e.message ?: defaultMessage
        _authState.value = AuthState.Error(e, errorMessage)
    }
}
