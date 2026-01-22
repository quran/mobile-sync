package com.quran.shared.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quran.shared.auth.di.AuthConfigFactory
import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.service.AuthService
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for authentication UI, providing a platform-agnostic interface for login and logout operations.
 *
 * This class coordinates the UI state by delegating authentication logic to the [AuthService].
 * It manages the authentication flow, error state, and session status across Android and iOS platforms.
 *
 * Prerequisites:
 * - The platform-specific authentication factory must be registered before calling [login].
 * - On Android: Requires initialization with `AndroidCodeAuthFlowFactory`.
 * - On iOS: Requires initialization with `IosCodeAuthFlowFactory`.
 *
 * @property authService The service handling the underlying OIDC logic and state management.
 */
class AuthViewModel(
    private val authService: AuthService = AuthConfigFactory.authService
) : ViewModel() {

    /** Current authentication state */
    val authState: StateFlow<AuthState> = authService.authState

    /**
     * Initiates the OAuth login flow.
     */
    fun login() {
        viewModelScope.launch {
            try {
                authService.login()
            } catch (e: Exception) {
                // Error is handled by the service state
            }
        }
    }

    /**
     * Signs out the user.
     */
    fun logout() {
        viewModelScope.launch {
            try {
                authService.logout()
            } catch (e: Exception) {
                // Error is handled by the service state
            }
        }
    }

    /**
     * Check if user is currently logged in.
     */
    fun isLoggedIn(): Boolean = authService.isLoggedIn()

    /**
     * Get current access token.
     */
    fun getAccessToken(): String? = authService.getAccessToken()

    /**
     * Resets the error state.
     */
    fun clearError() {
        authService.clearError()
    }
}