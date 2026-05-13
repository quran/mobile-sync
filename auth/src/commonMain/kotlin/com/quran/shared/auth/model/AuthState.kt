package com.quran.shared.auth.model

import com.quran.shared.auth.model.UserInfo

/**
 * Sealed class representing authentication state.
 *
 * The auth flow is handled entirely by the OIDC library's CodeAuthFlow,
 * so we only need states for: Idle, Loading, Success, and Error.
 */
sealed class AuthState {
    /** Initial state - user not logged in, ready to start */
    data object Idle : AuthState()

    /** Authentication in progress (browser may be open) */
    data object Loading : AuthState()

    /**
     * Successfully authenticated.
     *
     * [userInfo] is optional because OAuth token validity and profile availability are separate
     * concerns. A profile request can fail or cached profile data can be unavailable while the
     * stored token session is still valid for authenticated sync calls.
     */
    data class Success(val userInfo: UserInfo?) : AuthState()

    /** Authentication failed with an exception */
    data class Error(val exception: Exception, val message: String) : AuthState()
}
