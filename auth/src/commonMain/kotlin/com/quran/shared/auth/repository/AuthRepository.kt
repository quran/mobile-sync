package com.quran.shared.auth.repository

import com.quran.shared.auth.model.UserInfo
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
data class LogoutTokenMaterial(
    val refreshToken: String?,
    val idToken: String?
)

@HiddenFromObjC
class LogoutTokenCaptureException(
    cause: Exception
) : Exception(cause.message, cause)

@HiddenFromObjC
enum class RemoteLogoutOperation {
    REVOKE_REFRESH_TOKEN,
    END_SESSION
}

@HiddenFromObjC
data class RemoteLogoutFailure(
    val operation: RemoteLogoutOperation,
    val exception: Exception
)

/**
 * Repository handling authentication operations and token persistence.
 *
 * Separates business logic and data management from the UI layer (ViewModel).
 * Uses the OIDC library's CodeAuthFlow for browser-based authentication.
 */
@HiddenFromObjC
interface AuthRepository {
    /**
     * Performs the complete OAuth2 login flow.
     * This launches the browser, handles the redirect, and exchanges the code for tokens.
     *
     * @throws Exception if authentication fails or is cancelled
     */
    suspend fun login()

    /**
     * Performs a login flow while forcing re-authentication on the provider.
     * Equivalent to passing `prompt=login` in the OIDC authorization request.
     *
     * @throws Exception if authentication fails or is cancelled
     */
    suspend fun loginWithReauthentication()

    /**
     * Refreshes the access token if it's expired or near expiration.
     * @return true if token is valid (refreshed if needed), false if re-authentication is required.
     */
    suspend fun refreshTokensIfNeeded(): Boolean

    /**
     * Revokes current tokens and clears all local authentication data.
     */
    suspend fun logout()

    suspend fun captureLogoutTokenMaterial(): LogoutTokenMaterial

    suspend fun captureLogoutTokenMaterialAndClearLocalSession(): LogoutTokenMaterial {
        var tokenMaterial: LogoutTokenMaterial? = null
        var captureFailure: Exception? = null
        try {
            tokenMaterial = captureLogoutTokenMaterial()
        } catch (e: Exception) {
            captureFailure = e
        } finally {
            clearLocalSession()
        }
        captureFailure?.let { throw LogoutTokenCaptureException(it) }
        return requireNotNull(tokenMaterial)
    }

    suspend fun clearLocalSession()

    suspend fun attemptRemoteLogout(tokenMaterial: LogoutTokenMaterial): List<RemoteLogoutFailure>

    /**
     * Returns the current access token if available.
     */
    suspend fun getAccessToken(): String?

    /**
     * Checks if the user is currently authenticated with a valid session.
     */
    suspend fun isLoggedIn(): Boolean

    /**
     * Returns the current authenticated user info if available.
     */
    suspend fun getCurrentUser(): UserInfo?

    /**
     * Provides the headers required for authorized requests, refreshing the token if needed.
     * */
    suspend fun getAuthHeaders(): Map<String, String>
}

internal interface AuthRepositoryLoginCommitCallbacks : AuthRepository {
    suspend fun login(onDurableCommit: suspend () -> Unit)

    suspend fun loginWithReauthentication(onDurableCommit: suspend () -> Unit)
}
