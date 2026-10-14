package com.quran.shared.pipeline.integration

import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.LogoutTokenMaterial
import com.quran.shared.auth.repository.RemoteLogoutFailure
import com.quran.shared.auth.repository.RemoteLogoutMode
import com.quran.shared.auth.repository.RemoteLogoutOperation
import com.quran.shared.pipeline.integration.server.FakeSyncServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The OIDC boundary for one simulated device: the part of `OidcAuthRepository` that talks to the
 * browser login, the token endpoint and the token store. Login is not interactive; it signs in as
 * the account [signInAs] chose and gets a token from [server]. The session lives in this object,
 * so it survives `SyncDevice.restart()` like a persisted token store.
 */
class FakeAuthRepository(private val device: String, private val server: FakeSyncServer) : AuthRepository {
    @Volatile private var pendingAccount: String? = null
    @Volatile private var expireOnNextRefresh = false
    @Volatile private var remoteLogoutFails = false

    @Volatile var account: String? = null
        private set
    @Volatile var token: String? = null
        private set

    private val deniedHeaders = MutableStateFlow(0)

    /** How many times sync asked for headers and got none: each one is a run skipped as signed out. */
    val headerDenials: StateFlow<Int> get() = deniedHeaders

    /** Chooses the account the next [login] signs in to. */
    fun signInAs(account: String) {
        pendingAccount = account
    }

    /** The next token refresh fails and clears the session, without a `logout()`, like an expired refresh token. */
    fun expireSession() {
        expireOnNextRefresh = true
    }

    /** Replaces the access token, as a successful refresh does. */
    fun rotateToken() {
        val account = checkNotNull(account) { "Not signed in" }
        token = server.issueToken(account, device)
    }

    /** Remote token revocation fails during logout. */
    fun failRemoteLogout() {
        remoteLogoutFails = true
    }

    override suspend fun login() {
        val account = checkNotNull(pendingAccount) { "Call signInAs(account) before login()" }
        this.account = account
        token = server.issueToken(account, device)
        expireOnNextRefresh = false
    }

    override suspend fun loginWithReauthentication() = login()

    override suspend fun refreshTokensIfNeeded(): Boolean {
        if (expireOnNextRefresh && token != null) {
            expireOnNextRefresh = false
            clearLocalSession()
        }
        return token != null
    }

    override suspend fun logout() = clearLocalSession()

    override suspend fun captureLogoutTokenMaterial() = LogoutTokenMaterial(refreshToken = token?.let { "refresh-$it" }, idToken = null)

    override suspend fun clearLocalSession() {
        account = null
        token = null
    }

    override suspend fun attemptRemoteLogout(tokenMaterial: LogoutTokenMaterial, mode: RemoteLogoutMode): List<RemoteLogoutFailure> =
        if (remoteLogoutFails) {
            listOf(RemoteLogoutFailure(RemoteLogoutOperation.REVOKE_REFRESH_TOKEN, Exception("Token revocation failed")))
        } else {
            emptyList()
        }

    override suspend fun getAccessToken(): String? = token

    override suspend fun isLoggedIn(): Boolean = token != null

    override suspend fun getCurrentUser(): UserInfo? = account?.let { UserInfo(id = it, name = it) }

    override suspend fun getAuthHeaders(): Map<String, String> {
        val token = token?.takeIf { refreshTokensIfNeeded() } ?: run {
            deniedHeaders.update { it + 1 }
            return emptyMap()
        }
        return mapOf("Authorization" to "Bearer $token", "x-auth-token" to token, "x-client-id" to CLIENT_ID)
    }

    companion object {
        const val CLIENT_ID = "integration-test-client"
    }
}
