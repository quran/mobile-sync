package com.quran.shared.auth.repository

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.quran.shared.auth.di.AuthFlowFactoryProvider
import com.quran.shared.auth.persistence.AuthStorage
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.URLBuilder
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.OpenIdConnectException
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlow
import org.publicvalue.multiplatform.oidc.types.AuthCodeRequest
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Hosts forward warnings and errors to crash reporting, so a login the user cancelled or a newer session change
 * superseded logs at info without the throwable, while any other failed login logs at error with it.
 */
class OidcAuthRepositoryLoginLoggingTest {
    private data class Entry(val severity: Severity, val message: String, val throwable: Throwable?)

    private val entries = mutableListOf<Entry>()
    private val writer = object : LogWriter() {
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            entries += Entry(severity, message, throwable)
        }
    }
    private var previousMinSeverity = Severity.Verbose

    @BeforeTest
    fun listen() {
        previousMinSeverity = Logger.config.minSeverity
        Logger.setMinSeverity(Severity.Verbose)
        Logger.addLogWriter(writer)
    }

    @AfterTest
    fun stop() {
        try {
            Logger.setLogWriters(Logger.config.logWriterList - writer)
        } finally {
            Logger.setMinSeverity(previousMinSeverity)
        }
    }

    @Test
    fun `a login the user cancels logs at info without the throwable`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithLogin { throw OpenIdConnectException.AuthenticationCancelled() }

        assertFailsWith<OpenIdConnectException.AuthenticationCancelled> { repository.login() }

        val entry = loginEntry()
        assertEquals(Severity.Info, entry.severity)
        assertNull(entry.throwable)
    }

    @Test
    fun `a login a logout supersedes logs at info without the throwable`() = runTest(UnconfinedTestDispatcher()) {
        val storage = storage()
        val repository = repositoryWithLogin(storage) {
            // Another instance on the same storage logs out while the browser sheet is open.
            oidcRepository(storage).clearLocalSession()
            AccessTokenResponse(access_token = "login-access-token", token_type = "Bearer", expires_in = 3600)
        }

        assertFailsWith<IllegalStateException> { repository.login() }

        val entry = loginEntry()
        assertEquals(Severity.Info, entry.severity)
        assertNull(entry.throwable)
        assertNull(storage.retrieveStoredAccessToken())
    }

    @Test
    fun `any other failed login logs at error with the throwable`() = runTest(UnconfinedTestDispatcher()) {
        val failure = OpenIdConnectException.TechnicalFailure("unreadable", IllegalStateException("unexpected body"))
        val repository = repositoryWithLogin { throw failure }

        assertFailsWith<OpenIdConnectException.TechnicalFailure> { repository.login() }

        val entry = loginEntry()
        assertEquals(Severity.Error, entry.severity)
        assertSame(failure, entry.throwable)
    }

    /** The one entry the failed login logged. */
    private fun loginEntry(): Entry = entries.single { it.message.startsWith("Login") }

    private fun repositoryWithLogin(
        storage: AuthStorage = storage(),
        login: suspend () -> AccessTokenResponse
    ): OidcAuthRepository {
        AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(ScriptedLoginAuthFlow(login)))
        return oidcRepository(storage)
    }

    private fun storage() = AuthStorage(
        tokenStore = RecordingTokenStore(),
        settings = MapSettings().toSuspendSettings(),
        json = Json { ignoreUnknownKeys = true }
    )
}

/** Stands in for the browser sheet: the login answers with whatever [login] returns or throws. */
private class ScriptedLoginAuthFlow(private val login: suspend () -> AccessTokenResponse) : CodeAuthFlow {
    override suspend fun getAccessToken(
        configureAuthUrl: (URLBuilder.() -> Unit)?,
        configureTokenExchange: (HttpRequestBuilder.() -> Unit)?
    ): AccessTokenResponse = login()

    override suspend fun startLogin(configureAuthUrl: (URLBuilder.() -> Unit)?): AuthCodeRequest =
        throw UnsupportedOperationException("Not needed for this test")

    override suspend fun canContinueLogin(): Boolean = false

    override suspend fun continueLogin(
        configureTokenExchange: (HttpRequestBuilder.() -> Unit)?
    ): AccessTokenResponse = throw UnsupportedOperationException("Not needed for this test")
}
