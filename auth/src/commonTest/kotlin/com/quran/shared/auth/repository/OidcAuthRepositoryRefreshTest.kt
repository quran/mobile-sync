package com.quran.shared.auth.repository

import com.quran.shared.auth.persistence.AuthStorage
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.OpenIdConnectException
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A forced refresh after a 401, a refresh that fails for a transient reason, and a cancelled refresh. */
class OidcAuthRepositoryRefreshTest {

    @Test
    fun `a forced refresh replaces a token that has not expired locally`() = runTest(UnconfinedTestDispatcher()) {
        val storage = committedSession(expiresAt = Long.MAX_VALUE)
        val repository = oidcRepository(storage, refreshingClient(failure = null))

        assertTrue(repository.refreshTokensIfNeeded(), "valid locally: no refresh")
        assertEquals("old-access-token", repository.getAccessToken())

        assertTrue(repository.forceRefreshTokens())
        assertEquals("fresh-access-token", repository.getAccessToken())
    }

    @Test
    fun `a refresh that fails on the network keeps the session`() = runTest(UnconfinedTestDispatcher()) {
        val storage = committedSession(expiresAt = 0)
        val failure = OpenIdConnectException.TechnicalFailure("offline", IOException("offline"))
        val repository = oidcRepository(storage, refreshingClient(failure))

        assertFailsWith<OpenIdConnectException.TechnicalFailure> { repository.refreshTokensIfNeeded() }

        assertEquals("old-access-token", repository.getAccessToken(), "the session is kept")
        assertTrue(repository.isLoggedIn())
    }

    @Test
    fun `a refresh that times out keeps the session`() = runTest(UnconfinedTestDispatcher()) {
        val storage = committedSession(expiresAt = 0)
        val failure = OpenIdConnectException.TechnicalFailure("timeout", HttpRequestTimeoutException())
        val repository = oidcRepository(storage, refreshingClient(failure))

        assertFailsWith<OpenIdConnectException.TechnicalFailure> { repository.refreshTokensIfNeeded() }

        assertEquals("old-access-token", repository.getAccessToken())
    }

    /** TechnicalFailure wraps any error, not only network ones: a bad response body is not transient. */
    @Test
    fun `a refresh that fails for a technical reason other than the network ends the session`() = runTest(UnconfinedTestDispatcher()) {
        val storage = committedSession(expiresAt = 0)
        val failure = OpenIdConnectException.TechnicalFailure("unreadable", IllegalStateException("unexpected body"))
        val repository = oidcRepository(storage, refreshingClient(failure))

        assertFalse(repository.refreshTokensIfNeeded())

        assertNull(repository.getAccessToken())
    }

    /** Named like Ktor's timeout exceptions, which aren't all IOExceptions on every platform. */
    private class HttpRequestTimeoutException : Exception("timed out")

    @Test
    fun `a refresh the server fails with a 5xx keeps the session`() = runTest(UnconfinedTestDispatcher()) {
        val storage = committedSession(expiresAt = Long.MAX_VALUE)
        val failure = OpenIdConnectException.UnsuccessfulTokenRequest(
            "unavailable", HttpStatusCode.ServiceUnavailable, null, IllegalStateException("503")
        )
        val repository = oidcRepository(storage, refreshingClient(failure))

        assertFailsWith<OpenIdConnectException.UnsuccessfulTokenRequest> { repository.forceRefreshTokens() }

        assertEquals("old-access-token", repository.getAccessToken())
    }

    @Test
    fun `a refused refresh token ends the session`() = runTest(UnconfinedTestDispatcher()) {
        val storage = committedSession(expiresAt = Long.MAX_VALUE)
        val failure = OpenIdConnectException.UnsuccessfulTokenRequest(
            "invalid_grant", HttpStatusCode.BadRequest, null, IllegalStateException("400")
        )
        val repository = oidcRepository(storage, refreshingClient(failure))

        assertFalse(repository.forceRefreshTokens())

        assertNull(repository.getAccessToken())
        assertFalse(repository.isLoggedIn())
    }

    /** The OIDC library wraps the caller's cancellation in TechnicalFailure, as when logout cancels a sync. */
    @Test
    fun `a cancelled caller's refresh the OIDC library wraps is a cancellation that keeps the session`() =
        runTest(UnconfinedTestDispatcher()) {
            val logs = WarningLogs()
            val client = BlockingRefreshOidcClient(unusedTokens, wrapsCancellation = true)
            val repository = oidcRepository(committedSession(expiresAt = 0), client, logger = logs.logger)
            val refresh = async(start = CoroutineStart.UNDISPATCHED) { repository.refreshTokensIfNeeded() }
            client.refreshStarted.await()

            refresh.cancel()

            assertFailsWith<CancellationException> { refresh.await() }
            assertEquals("old-access-token", repository.getAccessToken(), "the session is kept")
            assertTrue(repository.isLoggedIn())
            assertEquals(emptyList(), logs.entries, "a cancellation is not a refresh failure")
        }

    /** A cancellation inside the library while the caller is still active, such as its own timeout. */
    @Test
    fun `a cancellation that is not the caller's keeps the session and fails the refresh`() =
        runTest(UnconfinedTestDispatcher()) {
            val failure = OpenIdConnectException.TechnicalFailure("cancelled", CancellationException("cancelled"))
            val repository = oidcRepository(committedSession(expiresAt = 0), refreshingClient(failure))

            assertFailsWith<OpenIdConnectException.TechnicalFailure> { repository.refreshTokensIfNeeded() }

            assertEquals("old-access-token", repository.getAccessToken(), "the session is kept")
            assertTrue(repository.isLoggedIn())
        }

    private val unusedTokens = AccessTokenResponse(
        access_token = "unused-access-token",
        token_type = "Bearer",
        expires_in = 3600
    )

    private suspend fun committedSession(expiresAt: Long): AuthStorage {
        val settings = MapSettings().toSuspendSettings()
        val storage = AuthStorage(
            tokenStore = RecordingTokenStore(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token"
            ),
            settings = settings,
            json = Json { ignoreUnknownKeys = true }
        )
        storage.storeSessionGeneration(1)
        storage.storeCommittedTokenGeneration(1)
        settings.putLong("token_expiration", expiresAt)
        return storage
    }

    private fun refreshingClient(failure: Exception?) = BlockingRefreshOidcClient(
        refreshResponse = AccessTokenResponse(
            access_token = "fresh-access-token",
            token_type = "Bearer",
            expires_in = 3600,
            refresh_token = "fresh-refresh-token"
        ),
        refreshFailure = failure
    ).also { it.refreshCanFinish.complete(Unit) }
}
