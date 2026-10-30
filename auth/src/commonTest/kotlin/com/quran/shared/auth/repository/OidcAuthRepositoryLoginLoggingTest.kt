package com.quran.shared.auth.repository

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import com.quran.shared.auth.di.AuthFlowFactoryProvider
import com.quran.shared.auth.persistence.AuthStorage
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.URLBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A login the user cancelled logs at info, a cancelled login logs nothing; a real failure logs at error. */
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
    fun `a login cancelled while the browser is open ends as cancellation without an error`() =
        runTest(UnconfinedTestDispatcher()) {
            val browserOpened = CompletableDeferred<Unit>()
            useAuthFlow {
                browserOpened.complete(Unit)
                browserUntilCancelled()
            }
            val repository = repository()
            var loginEnd: Throwable? = null
            val login = launch {
                try {
                    repository.login()
                } catch (e: Throwable) {
                    loginEnd = e
                    throw e
                }
            }

            browserOpened.await()
            login.cancelAndJoin()

            assertIs<CancellationException>(loginEnd)
            assertTrue(entries.none { it.severity >= Severity.Warn }, "no warning or error: $entries")
        }

    @Test
    fun `a failure carrying a cancellation while the login still runs logs at error`() =
        runTest(UnconfinedTestDispatcher()) {
            val failure = OpenIdConnectException.TechnicalFailure("cancelled", CancellationException("cancelled"))
            useAuthFlow { throw failure }

            assertFailsWith<OpenIdConnectException.TechnicalFailure> { repository().login() }

            val loginFailure = entries.single { it.message == "Login failed" }
            assertEquals(Severity.Error, loginFailure.severity)
            assertEquals(failure, loginFailure.throwable)
        }

    private fun repository(): OidcAuthRepository =
        oidcRepository(
            storage = AuthStorage(
                tokenStore = RecordingTokenStore(),
                settings = MapSettings().toSuspendSettings(),
                json = Json { ignoreUnknownKeys = true }
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

}

/** The platform's browser prompt, answering with [result]. */
internal class ScriptedAuthFlow(
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

/**
 * The iOS browser prompt left open until its caller is cancelled. The OIDC library's wrapExceptions reports that
 * cancellation as a TechnicalFailure wrapping the caller's JobCancellationException.
 */
internal suspend fun browserUntilCancelled(): Nothing =
    try {
        awaitCancellation()
    } catch (e: CancellationException) {
        throw OpenIdConnectException.TechnicalFailure(e.message ?: "Unknown error", e)
    }
