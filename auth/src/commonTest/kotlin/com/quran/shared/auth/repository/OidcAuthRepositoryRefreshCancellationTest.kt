package com.quran.shared.auth.repository

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import com.quran.shared.auth.TEST_CLIENT_ID
import com.quran.shared.auth.TEST_SUBJECT
import com.quran.shared.auth.di.AuthModule
import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.auth.testIdToken
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import com.russhwolf.settings.coroutines.SuspendSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A refresh whose caller goes away mid-request (a cancelled Swift Task, a cancelled sync run asking for headers) is
 * cancelled, not refused. The OIDC library reports the cancelled request as a TechnicalFailure, and the session must
 * survive it without a warning or an error. A refresh token the server refuses still ends the session.
 */
class OidcAuthRepositoryRefreshCancellationTest {
    private data class Entry(val severity: Severity, val message: String)

    private val entries = mutableListOf<Entry>()
    private val logger = Logger(
        StaticConfig(
            minSeverity = Severity.Verbose,
            logWriterList = listOf(
                object : LogWriter() {
                    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                        entries += Entry(severity, message)
                    }
                }
            )
        ),
        "OidcAuthRepository"
    )
    private val settings = NonSuspendingSettings()
    private val tokenStore = RecordingTokenStore()
    private val storage = AuthStorage(
        tokenStore = tokenStore,
        settings = settings,
        json = Json { ignoreUnknownKeys = true }
    )

    @Test
    fun `a refresh cancelled while the token request is in flight ends as cancellation and keeps the session`() =
        runTest(UnconfinedTestDispatcher()) {
            expiredSession()
            val requestStarted = CompletableDeferred<Unit>()
            val repository = oidcRepository(
                storage = storage,
                oidcClient = libraryOidcClient {
                    requestStarted.complete(Unit)
                    awaitCancellation()
                },
                logger = logger
            )
            var refreshed: Boolean? = null
            var refreshEnd: Throwable? = null
            val refresh = launch {
                try {
                    refreshed = repository.refreshTokensIfNeeded()
                } catch (e: Throwable) {
                    refreshEnd = e
                    throw e
                }
            }

            // The token request runs on the HTTP engine's threads, so wait for it rather than the test scheduler.
            requestStarted.await()
            refresh.cancelAndJoin()

            assertIs<CancellationException>(refreshEnd, "the refresh returned $refreshed")
            assertTrue(entries.none { it.severity >= Severity.Warn }, "no warning or error: $entries")
            assertTrue(repository.isLoggedIn())
            assertEquals("old-access-token", repository.getAccessToken())
            assertEquals("old-refresh-token", tokenStore.getRefreshToken())
            assertEquals(sessionUser, repository.getCurrentUser())
        }

    /**
     * The control for this harness: the real library reads the server's 400 `invalid_grant` as a refusal, which ends
     * the session. `OidcAuthRepositoryRefreshTest` covers the same case with a fake client throwing the refusal.
     */
    @Test
    fun `a refresh token the server refuses ends the session`() = runTest(UnconfinedTestDispatcher()) {
        expiredSession()
        val repository = oidcRepository(
            storage = storage,
            oidcClient = libraryOidcClient {
                respond(
                    content = """{"error":"invalid_grant","error_description":"The refresh token was revoked."}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            },
            logger = logger
        )

        assertFalse(repository.refreshTokensIfNeeded())

        assertFalse(repository.isLoggedIn())
        assertNull(tokenStore.getRefreshToken())
        assertNull(repository.getCurrentUser())
        val failure = entries.single { it.message == "Token refresh failed, clearing session" }
        assertEquals(Severity.Error, failure.severity)
    }

    /** A committed session for [sessionUser] whose access token is inside its refresh window. */
    private suspend fun expiredSession() {
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
        storage.storeSessionGeneration(1)
        storage.storeCommittedTokenGeneration(1)
        settings.putLong("token_expiration", 0L)
    }

    private companion object {
        val sessionUser = UserInfo(id = TEST_SUBJECT, name = "Old Name", email = "old@example.com")
    }
}

/**
 * The production OIDC client (the library's own) on the production auth HTTP client, with [authorizationServer]
 * answering its requests through Ktor's mock engine. Its calls run inside the library's wrapExceptions, so a request
 * cancelled with its caller fails as a TechnicalFailure wrapping the caller's cancellation, as on a device.
 */
internal fun libraryOidcClient(authorizationServer: MockRequestHandler): OpenIdConnectClient =
    AuthModule.createOpenIdConnectClient(
        config = AuthConfig(clientId = TEST_CLIENT_ID),
        httpClient = AuthModule.createAuthHttpClient(
            json = Json { ignoreUnknownKeys = true },
            engine = MockEngine(authorizationServer)
        )
    )

/**
 * Auth settings in memory that never suspend, so no read or write notices a cancelled caller. The platforms' stores
 * suspend, and a read after the caller was cancelled throws; with these, a cancelled refresh keeps the session only if
 * it never decides to end it.
 */
internal class NonSuspendingSettings(private val delegate: Settings = MapSettings()) : SuspendSettings {
    override suspend fun keys(): Set<String> = delegate.keys
    override suspend fun size(): Int = delegate.size
    override suspend fun clear() = delegate.clear()
    override suspend fun remove(key: String) = delegate.remove(key)
    override suspend fun hasKey(key: String): Boolean = delegate.hasKey(key)
    override suspend fun putInt(key: String, value: Int) = delegate.putInt(key, value)
    override suspend fun getInt(key: String, defaultValue: Int): Int = delegate.getInt(key, defaultValue)
    override suspend fun getIntOrNull(key: String): Int? = delegate.getIntOrNull(key)
    override suspend fun putLong(key: String, value: Long) = delegate.putLong(key, value)
    override suspend fun getLong(key: String, defaultValue: Long): Long = delegate.getLong(key, defaultValue)
    override suspend fun getLongOrNull(key: String): Long? = delegate.getLongOrNull(key)
    override suspend fun putString(key: String, value: String) = delegate.putString(key, value)
    override suspend fun getString(key: String, defaultValue: String): String = delegate.getString(key, defaultValue)
    override suspend fun getStringOrNull(key: String): String? = delegate.getStringOrNull(key)
    override suspend fun putFloat(key: String, value: Float) = delegate.putFloat(key, value)
    override suspend fun getFloat(key: String, defaultValue: Float): Float = delegate.getFloat(key, defaultValue)
    override suspend fun getFloatOrNull(key: String): Float? = delegate.getFloatOrNull(key)
    override suspend fun putDouble(key: String, value: Double) = delegate.putDouble(key, value)
    override suspend fun getDouble(key: String, defaultValue: Double): Double = delegate.getDouble(key, defaultValue)
    override suspend fun getDoubleOrNull(key: String): Double? = delegate.getDoubleOrNull(key)
    override suspend fun putBoolean(key: String, value: Boolean) = delegate.putBoolean(key, value)
    override suspend fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        delegate.getBoolean(key, defaultValue)
    override suspend fun getBooleanOrNull(key: String): Boolean? = delegate.getBooleanOrNull(key)
}
