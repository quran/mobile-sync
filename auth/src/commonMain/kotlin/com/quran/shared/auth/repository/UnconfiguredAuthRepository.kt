package com.quran.shared.auth.repository

import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.native.HiddenFromObjC

/**
 * Thrown when a build without authentication credentials attempts an interactive auth operation.
 */
class AuthNotConfiguredException : IllegalStateException(
    "Authentication is not configured for this build."
)

/**
 * Auth repository used when the managed graph is initialized without OIDC credentials.
 *
 * It keeps sync-capable apps on the managed [com.quran.shared.pipeline.QuranDataService] path while
 * making remote authentication unavailable. Sync attempts receive empty headers and no-op before
 * network work starts.
 */
@SingleIn(AppScope::class)
@HiddenFromObjC
class UnconfiguredAuthRepository @Inject constructor(
    private val authStorage: AuthStorage
) : AuthRepository {
    override suspend fun login() {
        throw AuthNotConfiguredException()
    }

    override suspend fun loginWithReauthentication() {
        throw AuthNotConfiguredException()
    }

    override suspend fun refreshTokensIfNeeded(): Boolean {
        clearLocalSession()
        return false
    }

    override suspend fun logout() {
        clearLocalSession()
    }

    override suspend fun captureLogoutTokenMaterial(): LogoutTokenMaterial =
        LogoutTokenMaterial(refreshToken = null, idToken = null)

    override suspend fun clearLocalSession() {
        // Clear stale token material so an install that moves between configured and unconfigured
        // builds cannot publish or reuse an old session without explicit credentials.
        authStorage.clearAllTokens()
    }

    override suspend fun attemptRemoteLogout(tokenMaterial: LogoutTokenMaterial): List<RemoteLogoutFailure> =
        emptyList()

    override suspend fun getAccessToken(): String? = null

    override suspend fun isLoggedIn(): Boolean = false

    override suspend fun getCurrentUser(): UserInfo? = null

    override suspend fun getAuthHeaders(): Map<String, String> = emptyMap()
}
