package com.quran.shared.auth.ui.model

import com.quran.shared.auth.model.UserInfo

/**
 * Sealed class representing authentication state.
 * 
 * The auth flow is handled entirely by the OIDC library's CodeAuthFlow,
 * so we only need states for: Idle, Loading, Success, and Error.
 */
sealed class AuthState {
    /** Initial state - user not logged in, ready to start */
    object Idle : AuthState()
    
    /** Authentication in progress (browser may be open) */
    object Loading : AuthState()
    
    /** Successfully authenticated with user info */
    data class Success(val userInfo: UserInfo) : AuthState()

    /** Authentication failed with an exception */
    data class Error(val exception: Exception) : AuthState()
}