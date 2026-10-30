package com.quran.shared.auth.service

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import com.quran.shared.auth.TEST_SUBJECT
import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.auth.repository.NonSuspendingSettings
import com.quran.shared.auth.repository.RecordingTokenStore
import com.quran.shared.auth.repository.libraryOidcClient
import com.quran.shared.auth.repository.oidcRepository
import com.quran.shared.auth.testIdToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Sync asks [AuthService.getAuthHeaders] for its headers, which refreshes a token near expiry. When the sync run is
 * cancelled while that refresh's request is in flight, the OIDC library reports a TechnicalFailure; the headers must
 * end as the cancellation and the published session must stay signed in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthServiceRefreshCancellationTest {
    /** [AuthService] runs its own work on Main. */
    private val main = StandardTestDispatcher()
    private val warnings = mutableListOf<String>()
    private val logger = Logger(
        StaticConfig(
            minSeverity = Severity.Verbose,
            logWriterList = listOf(
                object : LogWriter() {
                    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                        if (severity >= Severity.Warn) warnings += message
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
    private val sessionUser = UserInfo(id = TEST_SUBJECT, name = "Old Name", email = "old@example.com")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `headers whose refresh is cancelled mid-request end as cancellation and keep the session`() = runTest(main) {
        val requestStarted = CompletableDeferred<Unit>()
        val repository = oidcRepository(
            storage = storage,
            oidcClient = libraryOidcClient {
                requestStarted.complete(Unit)
                awaitCancellation()
            },
            logger = logger
        )
        signedInSession()
        val service = AuthService(repository)
        advanceUntilIdle()
        assertEquals(AuthState.Success(sessionUser), service.authState.value)
        // Expired only after the launch check on purpose: otherwise refreshSessionPublishedAtLaunch would run the
        // blocked refresh in the service's scope instead of the caller's.
        settings.putLong("token_expiration", 0L)

        var headers: Map<String, String>? = null
        var headersEnd: Throwable? = null
        val syncRun = launch {
            try {
                headers = service.getAuthHeaders()
            } catch (e: Throwable) {
                headersEnd = e
                throw e
            }
        }
        // The token request runs on the HTTP engine's threads, so wait for it rather than the test scheduler.
        requestStarted.await()
        syncRun.cancelAndJoin()
        advanceUntilIdle()

        assertIs<CancellationException>(headersEnd, "the headers were $headers")
        assertEquals(AuthState.Success(sessionUser), service.authState.value)
        assertEquals("old-access-token", service.getAccessToken())
        assertTrue(repository.isLoggedIn())
        assertEquals("old-refresh-token", tokenStore.getRefreshToken())
        assertEquals(emptyList(), warnings)
        service.clearAndJoin()
    }

    /** A committed session for [sessionUser] whose access token is valid for an hour. */
    private suspend fun signedInSession() {
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
    }
}
