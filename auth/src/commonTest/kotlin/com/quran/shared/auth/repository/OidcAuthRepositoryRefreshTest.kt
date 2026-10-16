package com.quran.shared.auth.repository

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import com.quran.shared.auth.persistence.AuthStorage
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.HttpException
import org.publicvalue.multiplatform.oidc.OpenIdConnectException
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse
import org.publicvalue.multiplatform.oidc.types.remote.ErrorResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A forced refresh after a 401, and a refresh that fails for a transient reason. */
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

    @Test
    fun `a refused refresh logs its summary without the failure or its secrets`() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<Pair<String, Throwable?>>()
        val writer = object : LogWriter() {
            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                logs += message to throwable
            }
        }
        val repository = oidcRepository(
            committedSession(expiresAt = Long.MAX_VALUE),
            refreshingClient(refusedTokenRequest()),
            logger = Logger(StaticConfig(Severity.Verbose, listOf(writer)), "OidcAuthRepository")
        )

        assertFalse(repository.forceRefreshTokens())

        assertEquals(
            "Token refresh failed, clearing session: UnsuccessfulTokenRequest(status=400, error=invalid_grant) " +
                "<- HttpException(status=400, error=invalid_grant)",
            logs.single().first
        )
        assertNull(logs.single().second, "the failure itself is never logged")
        listOf(ACCESS_TOKEN, AUTHORIZATION_CODE, "refresh_token=", "?code=", EMAIL).forEach { secret ->
            assertFalse(secret in logs.single().first, "'$secret' leaked into the log")
        }
    }

    /** A refused refresh shaped like the OIDC library's, with a secret in every message, body and error field. */
    private fun refusedTokenRequest(): OpenIdConnectException.UnsuccessfulTokenRequest {
        val redirectUrl = "https://app.example.com/callback?code=$AUTHORIZATION_CODE&state=secret-state"
        val body = """{"error":"invalid_grant","error_description":"$ACCESS_TOKEN for $EMAIL"}"""
        val error = ErrorResponse(ErrorResponse.Error.invalid_grant, "revoked for $EMAIL", redirectUrl, "secret-state")
        val formBody = "grant_type=refresh_token&refresh_token=secret-refresh-token"
        return OpenIdConnectException.UnsuccessfulTokenRequest(
            message = "Refresh failed: $body",
            statusCode = HttpStatusCode.BadRequest,
            errorResponse = error,
            cause = HttpException("POST $formBody", HttpStatusCode.BadRequest, body, error)
        )
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

private const val ACCESS_TOKEN = "secret-access-token"
private const val AUTHORIZATION_CODE = "secret-auth-code"
private const val EMAIL = "reader@example.com"
