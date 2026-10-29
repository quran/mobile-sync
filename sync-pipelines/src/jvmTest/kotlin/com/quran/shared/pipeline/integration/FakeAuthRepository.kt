package com.quran.shared.pipeline.integration

import com.quran.shared.auth.model.FetchedProfile
import com.quran.shared.auth.model.FetchedProfileWrite
import com.quran.shared.auth.model.ProfileFetchSession
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
 * browser login, the token endpoint and the token store. Login signs in as the account [signInAs]
 * chose and gets a token from [server]. It returns at once unless a test holds it in the "browser"
 * with [holdNextLogin], like a user who hasn't finished signing in.
 *
 * Like `OidcAuthRepository`, it keeps the session's tokens only in the graph's token store
 * ([useTokenStore]), which outlives the graph, so the session survives `SyncDevice.restart()`.
 *
 * Also like `OidcAuthRepository`, [refreshTokensIfNeeded] refreshes only when the token has expired
 * by the device's own clock ([expireLocally]); the server rejecting a token does not refresh it.
 * Tokens never expire locally unless a test says so.
 *
 * With [storesProfiles], it keeps the session's fetched profile as `OidcAuthRepository` does: for the session that
 * fetched it and its account only, shown over the user until the session ends.
 */
class FakeAuthRepository(private val device: String, private val server: FakeSyncServer) : AuthRepository {
    @Volatile private var pendingAccount: String? = null
    @Volatile private var expireOnNextRefresh = false
    @Volatile private var remoteLogoutFails = false
    @Volatile private var logoutHold: Hold? = null
    @Volatile private var loginHold: Hold? = null
    @Volatile private var expiredLocally = false
    @Volatile private var refuseForcedRefresh = false
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
    /** The next forced refresh (after a 401) is refused, like a revoked refresh token: it ends the session. */
    fun refuseNextForcedRefresh() {
        refuseForcedRefresh = true
    }

    fun expireSession() {
        expireOnNextRefresh = true
    }

    /**
     * The device's clock now says the access token has expired, so the next [refreshTokensIfNeeded]
     * gets a new one from the server, as `OidcAuthRepository` does inside its safety margin.
     */
    fun expireLocally() {
        expiredLocally = true
    }

    private val refreshCount = MutableStateFlow(0)

    /** How many times [refreshTokensIfNeeded] replaced the token. */
    val refreshes: StateFlow<Int> get() = refreshCount

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
     * began. Returns the hold; complete [Hold.release] to let the logout go on.
     */
    fun holdNextLogout(): Hold = Hold().also { logoutHold = it }

    /**
     * Holds the next login where `OidcAuthRepository` shows the browser and exchanges the code, before
     * any token is stored. Returns the hold; complete [Hold.release] to let the login go on.
     */
    fun holdNextLogin(): Hold = Hold().also { loginHold = it }

    /** Where a held call waits: [reached] completes when it gets there; complete [release] to let it go on. */
    class Hold internal constructor() {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    /** The current access token, read from the token store. */
    suspend fun accessToken(): String? = tokenStore.getAccessToken()

    /** Keeps tokens in [store], the device graph's `MobileSyncStorage` token store. */
    internal fun useTokenStore(store: TokenStore): FakeAuthRepository = also { tokenStore = store }

    override suspend fun login() {
        val account = checkNotNull(pendingAccount) { "Call signInAs(account) before login()" }
        loginHold?.let { hold ->
            loginHold = null
            hold.reached.complete(Unit)
            hold.release.await()
        }
        this.account = account
        newSession()
        tokenStore.saveTokens(server.issueToken(account, device), null, null)
        expireOnNextRefresh = false
        expiredLocally = false
    }

    override suspend fun refreshTokensIfNeeded(): Boolean {
        if (expireOnNextRefresh && accessToken() != null) {
            expireOnNextRefresh = false
            clearLocalSession()
        }
        val account = account
        if (expiredLocally && account != null && accessToken() != null) {
            expiredLocally = false
            tokenStore.saveTokens(server.issueToken(account, device), null, null)
            refreshCount.update { it + 1 }
        }
        return accessToken() != null
    }

    /** Like OidcAuthRepository after a 401: a new token from [server], or the end of a session [expireSession] doomed. */
    override suspend fun forceRefreshTokens(): Boolean {
        val account = account
        if (accessToken() == null || account == null) return false
        if (expireOnNextRefresh || refuseForcedRefresh) {
            expireOnNextRefresh = false
            refuseForcedRefresh = false
            clearLocalSession()
            return false
        }
        tokenStore.saveTokens(server.issueToken(account, device), null, null)
        refreshCount.update { it + 1 }
        return true
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
        newSession()
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

    /** The profile name the session reads from now on (the account's by default), like a name edited on the web. */
    @Volatile var profileName: String? = null

    /**
     * Whether a session keeps the profile the device fetches. Off by default: a session then has no profile to fetch,
     * so the device makes no profile request and the user stays the one [getCurrentUser] makes up.
     */
    @Volatile var storesProfiles = false

    @Volatile private var sessionGeneration = 0L
    @Volatile private var fetchedProfile: FetchedProfile? = null
    @Volatile private var profileAttemptedAt: Long? = null

    /** A sign-in or the session's end: the session's profile goes with it. */
    private fun newSession() {
        sessionGeneration += 1
        fetchedProfile = null
        profileAttemptedAt = null
    }

    override suspend fun getCurrentUser(): UserInfo? = account?.let { account ->
        val user = UserInfo(id = account, name = profileName ?: account)
        val profile = fetchedProfile?.takeIf { it.id == account } ?: return@let user
        user.copy(
            name = profile.name,
            firstName = profile.firstName,
            lastName = profile.lastName,
            email = profile.email ?: user.email,
            photoUrl = profile.photoUrl
        )
    }

    override suspend fun captureProfileFetchSession(): ProfileFetchSession? {
        if (!storesProfiles || accessToken() == null) return null
        val account = account ?: return null
        return ProfileFetchSession(sessionGeneration, account, fetchedProfile?.fetchedAt, profileAttemptedAt)
    }

    override suspend fun recordProfileFetchAttempt(session: ProfileFetchSession, attemptedAt: Long): Boolean {
        if (session.generation != sessionGeneration) return false
        profileAttemptedAt = attemptedAt
        return true
    }

    override suspend fun storeFetchedProfile(session: ProfileFetchSession, profile: FetchedProfile): FetchedProfileWrite =
        when {
            session.generation != sessionGeneration || account != session.accountId -> FetchedProfileWrite.SESSION_CHANGED
            profile.id != session.accountId -> FetchedProfileWrite.OTHER_ACCOUNT
            else -> FetchedProfileWrite.STORED.also { fetchedProfile = profile }
        }

    override suspend fun getAuthHeaders(): Map<String, String> {
        // Like OidcAuthRepository: refresh first, then read the (possibly new) token.
        val token = (if (refreshTokensIfNeeded()) accessToken() else null) ?: run {
            deniedHeaders.update { it + 1 }
            return emptyMap()
        }
        return mapOf("Authorization" to "Bearer $token", "x-auth-token" to token, "x-client-id" to CLIENT_ID)
    }

    companion object {
        const val CLIENT_ID = "integration-test-client"
    }
}
