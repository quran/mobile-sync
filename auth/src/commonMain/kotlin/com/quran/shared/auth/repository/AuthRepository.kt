package com.quran.shared.auth.repository

import com.quran.shared.auth.model.FetchedProfile
import com.quran.shared.auth.model.FetchedProfileWrite
import com.quran.shared.auth.model.ProfileFetchSession
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
enum class RemoteLogoutMode(
    val operations: List<RemoteLogoutOperation>
) {
    TOKEN_REVOCATION_ONLY(listOf(RemoteLogoutOperation.REVOKE_REFRESH_TOKEN)),
    TOKEN_REVOCATION_AND_END_SESSION(RemoteLogoutOperation.entries)
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
     * This launches the browser, handles the redirect, and exchanges the code for tokens. The provider always
     * asks for credentials again (`prompt=login` in the OIDC authorization request).
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
     * Refreshes the access token now, even if it has not expired locally: the server rejected it (a revocation,
     * or a device clock behind the server's).
     * @return true if a new token is stored; false if the session ended (the refresh token was refused) or there is none.
     * @throws Exception when the refresh failed for a transient reason (network, server error); the session is kept.
     */
    suspend fun forceRefreshTokens(): Boolean = refreshTokensIfNeeded()

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

    suspend fun attemptRemoteLogout(
        tokenMaterial: LogoutTokenMaterial,
        mode: RemoteLogoutMode
    ): List<RemoteLogoutFailure>

    /**
     * Returns the current access token if available.
     */
    suspend fun getAccessToken(): String?

    /**
     * Checks if the user is currently authenticated with a valid session.
     */
    suspend fun isLoggedIn(): Boolean

    /**
     * Returns the current authenticated user info if available: the ID token's user, with the session's fetched
     * profile on top (see [storeFetchedProfile]).
     */
    suspend fun getCurrentUser(): UserInfo?

    /**
     * The current session, captured before a profile fetch, with its last fetch and attempt; null without a committed
     * session or a known account. A repository without sessions to fetch for keeps the default.
     */
    suspend fun captureProfileFetchSession(): ProfileFetchSession? = null

    /**
     * Records that [session] tried to fetch its profile at [attemptedAt], if it is still the current session.
     * @return false when the session ended or changed, and nothing was written.
     */
    suspend fun recordProfileFetchAttempt(session: ProfileFetchSession, attemptedAt: Long): Boolean = false

    /**
     * Stores [profile] for [session] when [session] is still the current session (no logout or sign-in since it was
     * captured) and [profile] is its account's. [getCurrentUser] then shows it over the ID token's user, through later
     * token refreshes too, until the session ends.
     */
    suspend fun storeFetchedProfile(session: ProfileFetchSession, profile: FetchedProfile): FetchedProfileWrite =
        FetchedProfileWrite.SESSION_CHANGED

    /**
     * Provides the headers required for authorized requests, refreshing the token if needed.
     * */
    suspend fun getAuthHeaders(): Map<String, String>
}

internal interface AuthRepositoryLoginCommitCallbacks : AuthRepository {
    suspend fun login(onDurableCommit: suspend () -> Unit)
}
