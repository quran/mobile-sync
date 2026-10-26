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
 * another account would mix two accounts' data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OidcAuthRepositoryIdTokenRefreshTest {
    private val tokenStore = RecordingTokenStore()
    private val storage = AuthStorage(
        tokenStore = tokenStore,
        settings = MapSettings().toSuspendSettings(),
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
        val repository = repositoryRefreshingWith(refreshedIdToken)

        assertTrue(repository.forceRefreshTokens())

        assertEquals(
            UserInfo(id = TEST_SUBJECT, name = "New Name", email = "new@example.com"),
            repository.getCurrentUser()
        )
        assertEquals("fresh-access-token", repository.getAccessToken())
        assertEquals(refreshedIdToken, tokenStore.getIdToken())
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
        val repository = repositoryRefreshingWith(testIdToken(subject = "another-account"))

        assertFalse(repository.forceRefreshTokens())

        assertSessionEnded(repository)
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
            val repository = repositoryRefreshingWith(idToken)

            assertFalse(repository.forceRefreshTokens(), idToken)

            assertSessionEnded(repository)
        }
        assertTrue(logs.none { (_, message) -> "opaque-id-token" in message }, "token strings are never logged: $logs")
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

    /** A session stored before this version has no user if its `/userinfo` fetch failed; its ID token isn't read. */
    @Test
    fun `a refresh gives a session with no known account the user of its ID token`() =
        runTest(UnconfinedTestDispatcher()) {
            signedInSession(user = null)
            val repository = repositoryRefreshingWith(testIdToken(claims = mapOf("name" to "New Name")))
            assertNull(repository.getCurrentUser())

            assertTrue(repository.forceRefreshTokens())

            assertEquals(UserInfo(id = TEST_SUBJECT, name = "New Name"), repository.getCurrentUser())
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
    }

    private suspend fun assertSessionEnded(repository: OidcAuthRepository) {
        assertFalse(repository.isLoggedIn())
        assertNull(repository.getCurrentUser())
        assertNull(tokenStore.getAccessToken())
        assertNull(storage.retrieveUserInfo())
    }

    /** A committed session with [user], or, when it is null, one stored before this version without a user. */
    private suspend fun signedInSession(user: UserInfo? = sessionUser) {
        val generation = storage.retrieveSessionGeneration() + 1
        val tokens = TokenResponse(
            accessToken = "old-access-token",
            refreshToken = "old-refresh-token",
            idToken = testIdToken("login-id-token"),
            expiresIn = 3600,
            tokenType = "Bearer"
        )
        if (user == null) storage.storeTokens(tokens) else storage.storeNewSessionTokens(tokens, user)
        storage.storeSessionGeneration(generation)
        storage.storeCommittedTokenGeneration(generation)
    }

    /** A repository whose authorization server answers a refresh at once with [idToken]. */
    private fun repositoryRefreshingWith(idToken: String?): OidcAuthRepository {
        val client = BlockingRefreshOidcClient(
            refreshResponse = AccessTokenResponse(
                access_token = "fresh-access-token",
                token_type = "Bearer",
                expires_in = 3600,
                refresh_token = "fresh-refresh-token",
                id_token = idToken
            )
        ).also { it.refreshCanFinish.complete(Unit) }
        return oidcRepository(storage = storage, oidcClient = client, logger = logger)
    }
}
