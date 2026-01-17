package com.quran.shared.auth.ui.model

/**
 * Sealed class representing authentication state
 */
sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    object Success : AuthState()

    data class StartAuthFlow(val authUrl: String) : AuthState()
    data class Error(val exception: Exception) : AuthState()
}