package com.quran.shared.auth.repository

import com.quran.shared.auth.model.UserInfo

/**
 * Repository handling authentication operations and token persistence.
 *
 * Separates business logic and data management from the UI layer (ViewModel).
 * Uses the OIDC library's CodeAuthFlow for browser-based authentication.
 */
interface AuthRepository {
    /**
     * Performs the complete OAuth2 login flow.
     * This launches the browser, handles the redirect, and exchanges the code for tokens.
     *
     * @throws Exception if authentication fails or is cancelled
     */
    suspend fun login()

    /**
     * Refreshes the access token if it's expired or near expiration.
     * @return true if token is valid (refreshed if needed), false if re-authentication is required.
     */
    suspend fun refreshTokensIfNeeded(): Boolean

    /**
     * Revokes current tokens and clears all local authentication data.
     */
    suspend fun logout()

    /**
     * Returns the current access token if available.
     */
    fun getAccessToken(): String?

    /**
     * Checks if the user is currently authenticated with a valid session.
     */
    fun isLoggedIn(): Boolean

    /**
     * Returns the current authenticated user info if available.
     */
    fun getCurrentUser(): UserInfo?

    /**
     * Provides the headers required for authorized requests, refreshing the token if needed.
     * */
    suspend fun getAuthHeaders(): Map<String, String>
}