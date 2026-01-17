package com.quran.shared.auth.repository


/**
 * Repository handling authentication operations and token persistence.
 *
 * Separates business logic and data management from the UI layer (ViewModel).
 */
interface AuthRepository {
    /**
     * Prepares the OAuth2 authorization URL and stores necessary PKCE state.
     * @return The URL to open in a browser.
     */
    fun startLoginFlow(): String

    /**
     * Completes the OAuth2 flow by exchanging the authorization code for tokens.
     * @param redirectUri The full redirect URI received from the provider.
     * @throws Exception if validation fails or token exchange fails.
     */
    suspend fun handleRedirect(redirectUri: String)

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
     * Retrieves the stored state for manual validation if needed.
     */
    fun getStoredState(): String?
}
