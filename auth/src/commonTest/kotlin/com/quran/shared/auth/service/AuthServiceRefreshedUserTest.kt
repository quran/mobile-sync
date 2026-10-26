package com.quran.shared.auth.service

import com.quran.shared.auth.TEST_SUBJECT
import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.auth.repository.BlockingRefreshOidcClient
import com.quran.shared.auth.repository.OidcAuthRepository
import com.quran.shared.auth.repository.RecordingTokenStore
import com.quran.shared.auth.repository.oidcRepository
import com.quran.shared.auth.testIdToken
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.SuspendSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sync fetches its headers through [AuthService.getAuthHeaders], which refreshes a token near expiry. The refresh's ID
 * token replaces the stored user (a rename, say), and [AuthService.authState] must then carry the new user.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthServiceRefreshedUserTest {
    /** [AuthService] runs its own work on Main. */
    private val main = StandardTestDispatcher()

    /** On the test scheduler, so the service's work settles under [advanceUntilIdle]. */
    private val settings = RefreshedUserReads(MapSettings().toSuspendSettings(main))
    private val tokenStore = RecordingTokenStore()
    private val storage = AuthStorage(
        tokenStore = tokenStore,
        settings = settings,
        json = Json { ignoreUnknownKeys = true }
    )
    private val sessionUser = UserInfo(id = TEST_SUBJECT, name = "Old Name", email = "old@example.com")
    private val renamedUser = sessionUser.copy(name = "New Name")
    private val renamedIdToken = testIdToken(
        tokenId = "refreshed-id-token",
        claims = mapOf("name" to "New Name", "email" to "old@example.com")
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a refresh while fetching headers publishes the renamed user`() = runTest(main) {
        val service = signedInService(refreshingClient(renamedIdToken))
        val states = recordStates(service)
        tokenExpiresNow()

        val headers = service.getAuthHeaders()
        advanceUntilIdle()

        assertEquals("Bearer fresh-access-token", headers["Authorization"])
        assertEquals(listOf(AuthState.Success(sessionUser), AuthState.Success(renamedUser)), states)
        assertEquals("fresh-access-token", service.getAccessToken())
        service.clearAndJoin()
    }

    @Test
    fun `a refresh while fetching headers with the same profile publishes nothing new`() = runTest(main) {
        val client = refreshingClient(
            testIdToken(tokenId = "refreshed-id-token", claims = mapOf("name" to "Old Name", "email" to "old@example.com"))
        )
        val service = signedInService(client)
        val states = recordStates(service)
        tokenExpiresNow()

        val headers = service.getAuthHeaders()
        advanceUntilIdle()

        assertTrue(client.refreshStarted.isCompleted, "the headers needed a refresh")
        assertEquals("Bearer fresh-access-token", headers["Authorization"])
        assertEquals(listOf<AuthState>(AuthState.Success(sessionUser)), states)
        service.clearAndJoin()
    }

    @Test
    fun `a refresh while fetching headers without an ID token keeps the published user`() = runTest(main) {
        val client = refreshingClient(idToken = null)
        val service = signedInService(client)
        tokenExpiresNow()

        val headers = service.getAuthHeaders()

        assertTrue(client.refreshStarted.isCompleted, "the headers needed a refresh")
        assertEquals("Bearer fresh-access-token", headers["Authorization"])
        assertEquals(AuthState.Success(sessionUser), service.authState.value)
        assertEquals("fresh-access-token", service.getAccessToken())
        service.clearAndJoin()
    }

    @Test
    fun `a logout while the refreshed user is republished keeps it unpublished`() = runTest(main) {
        val repository = oidcRepository(storage, refreshingClient(renamedIdToken))
        val service = signedInService(repository)
        val states = recordStates(service)
        val republishing = CompletableDeferred<Unit>()
        val republishCanFinish = CompletableDeferred<Unit>()
        settings.onRefreshedUserRead = {
            republishing.complete(Unit)
            republishCanFinish.await()
        }
        tokenExpiresNow()

        val headers = async { service.getAuthHeaders() }
        runCurrent()
        assertTrue(republishing.isCompleted, "the service is reading the refreshed user")
        val logout = async { service.logout() }
        runCurrent()
        republishCanFinish.complete(Unit)

        assertEquals(emptyMap(), headers.await())
        logout.await()
        advanceUntilIdle()
        assertEquals(AuthState.Idle, service.authState.value)
        assertTrue(AuthState.Success(renamedUser) !in states, "$states")
        assertNull(service.getAccessToken())
        assertFalse(repository.isLoggedIn())
        service.clearAndJoin()
    }

    @Test
    fun `a session gone while the refreshed user is republished ends the published session`() = runTest(main) {
        val service = signedInService(refreshingClient(renamedIdToken))
        val states = recordStates(service)
        settings.onRefreshedUserRead = { storage.clearAllTokens() }
        tokenExpiresNow()

        val headers = service.getAuthHeaders()
        advanceUntilIdle()

        assertEquals(emptyMap(), headers)
        assertEquals(AuthState.Idle, service.authState.value)
        assertTrue(AuthState.Success(renamedUser) !in states, "$states")
        assertNull(service.getAccessToken())
        service.clearAndJoin()
    }

    @Test
    fun `a republish that fails is retried by the next headers`() = runTest(main) {
        val client = refreshingClient(renamedIdToken)
        val service = signedInService(client)
        settings.onRefreshedUserRead = { throw IllegalStateException("storage read failed") }
        tokenExpiresNow()

        val headers = service.getAuthHeaders()

        assertEquals("Bearer fresh-access-token", headers["Authorization"], "the refreshed token is still usable")
        assertEquals(AuthState.Success(sessionUser), service.authState.value)

        assertEquals("Bearer fresh-access-token", service.getAuthHeaders()["Authorization"])

        assertEquals(AuthState.Success(renamedUser), service.authState.value)
        assertEquals("fresh-access-token", service.getAccessToken())
        service.clearAndJoin()
    }

    @Test
    fun `a republish that is cancelled is retried by the next headers`() = runTest(main) {
        val service = signedInService(refreshingClient(renamedIdToken))
        val republishing = CompletableDeferred<Unit>()
        settings.onRefreshedUserRead = {
            republishing.complete(Unit)
            CompletableDeferred<Unit>().await()
        }
        tokenExpiresNow()

        val headers = async { service.getAuthHeaders() }
        runCurrent()
        assertTrue(republishing.isCompleted, "the service is reading the refreshed user")
        headers.cancel()
        advanceUntilIdle()
        assertEquals(AuthState.Success(sessionUser), service.authState.value)

        assertEquals("Bearer fresh-access-token", service.getAuthHeaders()["Authorization"])

        assertEquals(AuthState.Success(renamedUser), service.authState.value)
        service.clearAndJoin()
    }

    /** The refresh itself is still in flight when the user logs out, so it is never stored. */
    @Test
    fun `a logout during a refresh for headers does not publish the refreshed user`() = runTest(main) {
        val client = BlockingRefreshOidcClient(refreshResponse = refreshResponse(renamedIdToken))
        val repository = oidcRepository(storage, client)
        val service = signedInService(repository)
        val states = recordStates(service)
        tokenExpiresNow()

        val headers = async { service.getAuthHeaders() }
        runCurrent()
        assertTrue(client.refreshStarted.isCompleted, "the headers' refresh is in flight")
        service.logout()
        client.refreshCanFinish.complete(Unit)

        assertEquals(emptyMap(), headers.await())
        advanceUntilIdle()
        assertEquals(AuthState.Idle, service.authState.value)
        assertTrue(AuthState.Success(renamedUser) !in states, "$states")
        assertFalse(repository.isLoggedIn())
        assertNull(storage.retrieveUserInfo())
        service.clearAndJoin()
    }

    /** A service whose stored session for [sessionUser] it has published. */
    private suspend fun TestScope.signedInService(client: BlockingRefreshOidcClient): AuthService =
        signedInService(oidcRepository(storage, client))

    private suspend fun TestScope.signedInService(repository: OidcAuthRepository): AuthService {
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
        storage.storeSessionGeneration(generation)
        storage.storeCommittedTokenGeneration(generation)
        val service = AuthService(repository)
        advanceUntilIdle()
        assertEquals(AuthState.Success(sessionUser), service.authState.value)
        return service
    }

    /** The device's clock now puts the access token inside its refresh window. */
    private suspend fun tokenExpiresNow() {
        settings.putLong("token_expiration", 0L)
    }

    private fun TestScope.recordStates(service: AuthService): List<AuthState> {
        val states = mutableListOf<AuthState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            service.authState.toList(states)
        }
        return states
    }

    /** An authorization server that answers a refresh at once with [idToken]. */
    private fun refreshingClient(idToken: String?): BlockingRefreshOidcClient =
        BlockingRefreshOidcClient(refreshResponse = refreshResponse(idToken))
            .also { it.refreshCanFinish.complete(Unit) }

    private fun refreshResponse(idToken: String?) = AccessTokenResponse(
        access_token = "fresh-access-token",
        token_type = "Bearer",
        expires_in = 3600,
        refresh_token = "fresh-refresh-token",
        id_token = idToken
    )
}

/**
 * Settings that run [onRefreshedUserRead] once, before the first read of the stored user after a refresh renamed it.
 * Only the service's republish reads it then, so the hook steps in between the refresh and the publication.
 */
private class RefreshedUserReads(private val delegate: SuspendSettings) : SuspendSettings by delegate {
    var onRefreshedUserRead: (suspend () -> Unit)? = null

    override suspend fun getStringOrNull(key: String): String? {
        val hook = onRefreshedUserRead
        if (hook != null && key == "user_info" && delegate.getStringOrNull(key)?.contains("New Name") == true) {
            onRefreshedUserRead = null
            hook()
        }
        return delegate.getStringOrNull(key)
    }
}
