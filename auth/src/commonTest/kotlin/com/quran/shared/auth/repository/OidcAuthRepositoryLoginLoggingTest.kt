package com.quran.shared.auth.repository

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import com.quran.shared.auth.di.AuthFlowFactoryProvider
import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.auth.testIdToken
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.URLBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectException
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlow
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlowFactory
import org.publicvalue.multiplatform.oidc.flows.EndSessionFlow
import org.publicvalue.multiplatform.oidc.types.AuthCodeRequest
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A login the user cancelled, or one a newer session change superseded, logs at info; a real failure logs at error. */
@OptIn(ExperimentalCoroutinesApi::class)
class OidcAuthRepositoryLoginLoggingTest {
    private data class Entry(val severity: Severity, val message: String, val throwable: Throwable?)

    private val entries = mutableListOf<Entry>()
    private val logger = Logger(
        StaticConfig(
            minSeverity = Severity.Verbose,
            logWriterList = listOf(
                object : LogWriter() {
                    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                        entries += Entry(severity, message, throwable)
                    }
                }
            )
        ),
        "OidcAuthRepository"
    )

    @Test
    fun `a login the user cancelled at the system prompt logs at info`() = runTest(UnconfinedTestDispatcher()) {
        useAuthFlow { throw OpenIdConnectException.AuthenticationCancelled() }

        assertFailsWith<OpenIdConnectException.AuthenticationCancelled> { repository().login() }

        assertTrue(entries.none { it.severity >= Severity.Warn }, "no warning or error: $entries")
        val loginEnd = entries.single { it.message == "Login ended without a session: AuthenticationCancelled" }
        assertEquals(Severity.Info, loginEnd.severity)
        assertNull(loginEnd.throwable)
    }

    @Test
    fun `a login that fails for another reason logs at error with the throwable`() = runTest(UnconfinedTestDispatcher()) {
        val failure = OpenIdConnectException.AuthenticationFailure("invalid_client")
        useAuthFlow { throw failure }

        assertFailsWith<OpenIdConnectException.AuthenticationFailure> { repository().login() }

        val loginFailure = entries.single { it.message == "Login failed" }
        assertEquals(Severity.Error, loginFailure.severity)
        assertEquals(failure, loginFailure.throwable)
    }

    @Test
    fun `a superseded login whose ID token is unusable ends as superseded`() = runTest(UnconfinedTestDispatcher()) {
        val response = CompletableDeferred<AccessTokenResponse>()
        useAuthFlow { response.await() }
        val oidcClient = BlockingRefreshOidcClient(
            refreshResponse = AccessTokenResponse(access_token = "unused", token_type = "Bearer", expires_in = 3600)
        )
        val repository = repository(oidcClient)
        val login = async(start = CoroutineStart.UNDISPATCHED) { runCatching { repository.login() } }

        repository.clearLocalSession()
        response.complete(
            AccessTokenResponse(
                access_token = "access-token",
                token_type = "Bearer",
                expires_in = 3600,
                refresh_token = "refresh-token",
                id_token = testIdToken(audience = listOf("other-client"))
            )
        )

        val failure = login.await().exceptionOrNull()
        assertEquals("LoginSupersededException", failure?.let { it::class.simpleName })
        assertTrue(entries.none { it.severity >= Severity.Warn }, "no warning or error: $entries")
        assertEquals(Severity.Info, entries.single { "LoginSupersededException" in it.message }.severity)
        assertEquals(listOf("refresh-token"), oidcClient.revokedTokens, "the unusable grant is still revoked")
        assertFalse(repository.isLoggedIn())
    }

    private fun repository(oidcClient: OpenIdConnectClient? = null): OidcAuthRepository =
        oidcRepository(
            storage = AuthStorage(
                tokenStore = RecordingTokenStore(),
                settings = MapSettings().toSuspendSettings(),
                json = Json { ignoreUnknownKeys = true }
            ),
            oidcClient = oidcClient ?: BlockingRefreshOidcClient(
                refreshResponse = AccessTokenResponse(access_token = "unused", token_type = "Bearer", expires_in = 3600)
            ),
            logger = logger
        )

    private fun useAuthFlow(getAccessToken: suspend () -> AccessTokenResponse) {
        AuthFlowFactoryProvider.initialize(
            object : CodeAuthFlowFactory {
                override fun createAuthFlow(client: OpenIdConnectClient): CodeAuthFlow = ScriptedAuthFlow(getAccessToken)

                override fun createEndSessionFlow(client: OpenIdConnectClient): EndSessionFlow =
                    throw UnsupportedOperationException("Not needed for this test")
            }
        )
    }

    /** The platform's browser prompt, answering with [result]. */
    private class ScriptedAuthFlow(
        private val result: suspend () -> AccessTokenResponse
    ) : CodeAuthFlow {
        override suspend fun getAccessToken(
            configureAuthUrl: (URLBuilder.() -> Unit)?,
            configureTokenExchange: (HttpRequestBuilder.() -> Unit)?
        ): AccessTokenResponse = result()

        override suspend fun startLogin(configureAuthUrl: (URLBuilder.() -> Unit)?): AuthCodeRequest =
            throw UnsupportedOperationException("Not needed for this test")

        override suspend fun canContinueLogin(): Boolean = false

        override suspend fun continueLogin(
            configureTokenExchange: (HttpRequestBuilder.() -> Unit)?
        ): AccessTokenResponse = throw UnsupportedOperationException("Not needed for this test")
    }
}
