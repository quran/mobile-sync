package com.quran.shared.auth.repository

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import com.quran.shared.auth.TEST_SUBJECT
import com.quran.shared.auth.di.AuthFlowFactoryProvider
import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.auth.testIdToken
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A refresh's ID token must name the session's account: uploads go to the access token's account, so tokens for
 * another account would mix two accounts' data. A response that can't be used has its refresh token revoked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OidcAuthRepositoryIdTokenRefreshTest {
    private val tokenStore = RecordingTokenStore()
    private val settings = MapSettings().toSuspendSettings()
    private val storage = AuthStorage(
        tokenStore = tokenStore,
        settings = settings,
        json = Json { ignoreUnknownKeys = true }
    )
    private val sessionUser = UserInfo(id = TEST_SUBJECT, name = "Old Name", email = "old@example.com")
    private val logs = mutableListOf<Pair<Severity, String>>()
    private val logger = Logger(
        StaticConfig(
            minSeverity = Severity.Verbose,
            logWriterList = listOf(
                object : LogWriter() {
                    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                        logs += severity to message
                    }
                }
            )
        ),
        "OidcAuthRepository"
    )

    @Test
    fun `a refreshed ID token for the same account updates the profile`() = runTest(UnconfinedTestDispatcher()) {
        signedInSession()
        val refreshedIdToken = testIdToken(
            tokenId = "refreshed-id-token",
            claims = mapOf("name" to "New Name", "email" to "new@example.com")
        )
        val client = refreshingClient(refreshedIdToken)
        val repository = oidcRepository(storage, client, logger)

        assertTrue(repository.forceRefreshTokens())

        assertEquals(
            UserInfo(id = TEST_SUBJECT, name = "New Name", email = "new@example.com"),
            repository.getCurrentUser()
        )
        assertEquals("fresh-access-token", repository.getAccessToken())
        assertEquals(refreshedIdToken, tokenStore.getIdToken())
        assertEquals(emptyList(), client.revokedTokens, "a usable grant is kept")
    }

    @Test
    fun `a refresh without an ID token keeps the user`() = runTest(UnconfinedTestDispatcher()) {
        signedInSession()
        val repository = repositoryRefreshingWith(idToken = null)

        assertTrue(repository.forceRefreshTokens())

        assertEquals(sessionUser, repository.getCurrentUser())
        assertEquals("fresh-access-token", repository.getAccessToken())
        assertEquals(testIdToken("login-id-token"), tokenStore.getIdToken(), "the session's ID token is kept")
    }

    @Test
    fun `a refreshed ID token for another account ends the session`() = runTest(UnconfinedTestDispatcher()) {
        signedInSession()
        val client = refreshingClient(testIdToken(subject = "another-account"))
        val repository = oidcRepository(storage, client, logger)

        assertFalse(repository.forceRefreshTokens())

        assertSessionEnded(repository)
        assertEquals(listOf("fresh-refresh-token"), client.revokedTokens, "the other account's grant is revoked")
        assertTrue(
            logs.any { (severity, message) -> severity == Severity.Error && "another account" in message },
            "$logs"
        )
    }

    @Test
    fun `an unusable refreshed ID token ends the session`() = runTest(UnconfinedTestDispatcher()) {
        listOf(
            testIdToken(audience = listOf("other-client")),
            testIdToken(issuer = "https://accounts.example.com"),
            "opaque-id-token"
        ).forEach { idToken ->
            signedInSession()
            val client = refreshingClient(idToken)
            val repository = oidcRepository(storage, client, logger)

            assertFalse(repository.forceRefreshTokens(), idToken)

            assertSessionEnded(repository)
            assertEquals(listOf("fresh-refresh-token"), client.revokedTokens, idToken)
        }
        assertTrue(logs.none { (_, message) -> "opaque-id-token" in message }, "token strings are never logged: $logs")
    }

    @Test
    fun `an expiry refresh with an ID token for another account ends the session`() =
        runTest(UnconfinedTestDispatcher()) {
            signedInSession(expiresAt = 0)
            val repository = repositoryRefreshingWith(testIdToken(subject = "another-account"))

            assertFalse(repository.refreshTokensIfNeeded())
            assertEquals(emptyMap(), repository.getAuthHeaders())

            assertSessionEnded(repository)
        }

    @Test
    fun `a failed revoke of an unusable refresh still ends the session`() = runTest(UnconfinedTestDispatcher()) {
        listOf(testIdToken(subject = "another-account"), testIdToken(audience = listOf("other-client"))).forEach {
            signedInSession()
            val client = refreshingClient(it, revokeFailure = IllegalStateException("revocation endpoint unreachable"))
            val repository = oidcRepository(storage, client, logger)

            assertFalse(repository.forceRefreshTokens())

            assertSessionEnded(repository)
            assertEquals(listOf("fresh-refresh-token"), client.revokedTokens)
        }
        assertEquals(
            2,
            logs.count { (severity, message) -> severity == Severity.Warn && "Failed to revoke" in message },
            "$logs"
        )
        assertTrue(logs.none { (_, message) -> "fresh-refresh-token" in message }, "token strings are never logged")
    }

    @Test
    fun `a stale refresh with an ID token for another account cannot end a newer session`() =
        runTest(UnconfinedTestDispatcher()) {
            assertStaleRefreshKeepsNewerSession(testIdToken("stale-id-token", subject = "another-account"))
        }

    @Test
    fun `a stale refresh with an unusable ID token cannot end a newer session`() =
        runTest(UnconfinedTestDispatcher()) {
            assertStaleRefreshKeepsNewerSession(testIdToken("stale-id-token", audience = listOf("other-client")))
        }

    /**
     * A refresh for the signed-in session is in flight when a new login replaces the session; it then returns
     * [staleIdToken], which would end the session it was started for. The newer session must survive it.
     */
    private suspend fun TestScope.assertStaleRefreshKeepsNewerSession(staleIdToken: String) {
        signedInSession()
        val staleClient = BlockingRefreshOidcClient(
            refreshResponse = AccessTokenResponse(
                access_token = "stale-access-token",
                token_type = "Bearer",
                expires_in = 3600,
                refresh_token = "stale-refresh-token",
                id_token = staleIdToken
            )
        )
        val repository = oidcRepository(storage, staleClient, logger)
        val staleRefresh = async(start = CoroutineStart.UNDISPATCHED) { repository.forceRefreshTokens() }
        withContext(Dispatchers.Default) {
            withTimeout(1_000) {
                staleClient.refreshStarted.await()
            }
        }

        val newLogin = BlockingLoginAuthFlow(
            pendingResponse = AccessTokenResponse(access_token = "unused", token_type = "Bearer", expires_in = 3600),
            loginResponse = AccessTokenResponse(
                access_token = "new-access-token",
                token_type = "Bearer",
                expires_in = 3600,
                refresh_token = "new-refresh-token",
                id_token = testIdToken("new-id-token", subject = "new-user", claims = mapOf("name" to "New User"))
            )
        )
        AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(newLogin))
        newLogin.loginCanFinish.complete(Unit)
        repository.login()
        val newUser = UserInfo(id = "new-user", name = "New User")
        assertEquals(newUser, repository.getCurrentUser())

        staleClient.refreshCanFinish.complete(Unit)

        assertFalse(staleRefresh.await())
        assertTrue(repository.isLoggedIn())
        assertEquals(newUser, repository.getCurrentUser())
        assertEquals(newUser, storage.retrieveUserInfo())
        assertEquals("new-access-token", tokenStore.getAccessToken())
        assertEquals("new-refresh-token", tokenStore.getRefreshToken())
        assertEquals(listOf("stale-refresh-token"), staleClient.revokedTokens, "only the stale grant is revoked")
    }

    private suspend fun assertSessionEnded(repository: OidcAuthRepository) {
        assertFalse(repository.isLoggedIn())
        assertNull(repository.getCurrentUser())
        assertNull(tokenStore.getAccessToken())
        assertNull(storage.retrieveUserInfo())
    }

    /** A session as sign-in stores it: the tokens and the user from the ID token, committed together. */
    private suspend fun signedInSession(expiresAt: Long? = null) {
        val generation = storage.retrieveSessionGeneration() + 1
        storage.storeNewSessionTokens(
            TokenResponse(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = testIdToken("login-id-token"),
                expiresIn = 3600,
                tokenType = "Bearer"
            ),
            sessionUser
        )
        expiresAt?.let { settings.putLong("token_expiration", it) }
        storage.storeSessionGeneration(generation)
        storage.storeCommittedTokenGeneration(generation)
    }

    private fun repositoryRefreshingWith(idToken: String?): OidcAuthRepository =
        oidcRepository(storage = storage, oidcClient = refreshingClient(idToken), logger = logger)

    /** An authorization server whose refresh answers at once with [idToken], and whose revocation may fail. */
    private fun refreshingClient(idToken: String?, revokeFailure: Exception? = null) =
        BlockingRefreshOidcClient(
            refreshResponse = AccessTokenResponse(
                access_token = "fresh-access-token",
                token_type = "Bearer",
                expires_in = 3600,
                refresh_token = "fresh-refresh-token",
                id_token = idToken
            ),
            revokeFailure = revokeFailure
        ).also { it.refreshCanFinish.complete(Unit) }
}
