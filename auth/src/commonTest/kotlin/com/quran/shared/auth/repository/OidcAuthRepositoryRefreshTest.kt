package com.quran.shared.auth.repository

import com.quran.shared.auth.persistence.AuthStorage
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import io.ktor.http.HttpStatusCode
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

/** S3 and S25: a forced refresh after a 401, and a refresh that fails for a transient reason. */
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
