package com.quran.shared.pipeline.integration

import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.LogoutTokenMaterial
import com.quran.shared.auth.repository.RemoteLogoutFailure
import com.quran.shared.auth.repository.RemoteLogoutMode
import com.quran.shared.auth.repository.RemoteLogoutOperation
import com.quran.shared.pipeline.integration.server.FakeSyncServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.publicvalue.multiplatform.oidc.tokenstore.TokenStore

/**
 * The OIDC boundary for one simulated device: the part of `OidcAuthRepository` that talks to the
 * browser login, the token endpoint and the token store. Login is not interactive; it signs in as
 * the account [signInAs] chose and gets a token from [server].
 *
 * Like `OidcAuthRepository`, it keeps the session's tokens only in the graph's token store
 * ([useTokenStore]), which outlives the graph, so the session survives `SyncDevice.restart()`.
 */
class FakeAuthRepository(private val device: String, private val server: FakeSyncServer) : AuthRepository {
    @Volatile private var pendingAccount: String? = null
    @Volatile private var expireOnNextRefresh = false
    @Volatile private var remoteLogoutFails = false
    @Volatile private var logoutHold: LogoutHold? = null
    private lateinit var tokenStore: TokenStore

    /** The account of the latest login; null after a logout or an expired session. */
    @Volatile var account: String? = null
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
    suspend fun rotateToken() {
        val account = checkNotNull(account) { "Not signed in" }
        tokenStore.saveTokens(server.issueToken(account, device), null, null)
    }

    /** Remote token revocation fails during logout. */
    fun failRemoteLogout() {
        remoteLogoutFails = true
    }

    /**
     * Holds the next logout while it captures the tokens to revoke, which is after the managed reset
     * began. Returns the hold; complete [LogoutHold.release] to let the logout go on.
     */
    fun holdNextLogout(): LogoutHold = LogoutHold().also { logoutHold = it }

    class LogoutHold internal constructor() {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    /** The current access token, read from the token store. */
    suspend fun accessToken(): String? = tokenStore.getAccessToken()

    /** Keeps tokens in [store], the device graph's `MobileSyncStorage` token store. */
    internal fun useTokenStore(store: TokenStore): FakeAuthRepository = also { tokenStore = store }

    override suspend fun login() {
        val account = checkNotNull(pendingAccount) { "Call signInAs(account) before login()" }
        this.account = account
        tokenStore.saveTokens(server.issueToken(account, device), null, null)
        expireOnNextRefresh = false
    }

    override suspend fun loginWithReauthentication() = login()

    override suspend fun refreshTokensIfNeeded(): Boolean {
        if (expireOnNextRefresh && accessToken() != null) {
            expireOnNextRefresh = false
            clearLocalSession()
        }
        return accessToken() != null
    }

    override suspend fun logout() = clearLocalSession()

    override suspend fun captureLogoutTokenMaterial(): LogoutTokenMaterial {
        logoutHold?.let { hold ->
            logoutHold = null
            hold.reached.complete(Unit)
            hold.release.await()
        }
        return LogoutTokenMaterial(refreshToken = accessToken()?.let { "refresh-$it" }, idToken = null)
    }

    override suspend fun clearLocalSession() {
        account = null
        tokenStore.removeTokens()
    }

    override suspend fun attemptRemoteLogout(tokenMaterial: LogoutTokenMaterial, mode: RemoteLogoutMode): List<RemoteLogoutFailure> =
        if (remoteLogoutFails) {
            listOf(RemoteLogoutFailure(RemoteLogoutOperation.REVOKE_REFRESH_TOKEN, Exception("Token revocation failed")))
        } else {
            emptyList()
        }

    override suspend fun getAccessToken(): String? = accessToken()

    override suspend fun isLoggedIn(): Boolean = accessToken() != null

    override suspend fun getCurrentUser(): UserInfo? = account?.let { UserInfo(id = it, name = it) }

    override suspend fun getAuthHeaders(): Map<String, String> {
        val token = accessToken()?.takeIf { refreshTokensIfNeeded() } ?: run {
            deniedHeaders.update { it + 1 }
            return emptyMap()
        }
        return mapOf("Authorization" to "Bearer $token", "x-auth-token" to token, "x-client-id" to CLIENT_ID)
    }

    companion object {
        const val CLIENT_ID = "integration-test-client"
    }
}
