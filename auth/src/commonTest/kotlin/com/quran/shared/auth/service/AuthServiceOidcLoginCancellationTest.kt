package com.quran.shared.auth.service

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import com.quran.shared.auth.di.AuthFlowFactoryProvider
import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.model.AuthenticationCancelledException
import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.auth.repository.RecordingTokenStore
import com.quran.shared.auth.repository.ScriptedAuthFlow
import com.quran.shared.auth.repository.StaticCodeAuthFlowFactory
import com.quran.shared.auth.repository.browserUntilCancelled
import com.quran.shared.auth.repository.oidcRepository
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * A second sign-in tap while the browser is open waits on the first sign-in. When the first caller goes away, the OIDC
 * library reports the cancelled browser flow as a TechnicalFailure; the waiting caller must see a cancelled sign-in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthServiceOidcLoginCancellationTest {
    private val dispatcher = StandardTestDispatcher()
    private val errors = mutableListOf<String>()
    private val logger = Logger(
        StaticConfig(
            minSeverity = Severity.Verbose,
            logWriterList = listOf(
                object : LogWriter() {
                    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                        if (severity >= Severity.Warn) errors += message
                    }
                }
            )
        ),
        "OidcAuthRepository"
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a waiting sign in ends as cancelled when the first caller is cancelled in the browser`() =
        runTest(dispatcher) {
            val browserOpened = CompletableDeferred<Unit>()
            var browserOpens = 0
            AuthFlowFactoryProvider.initialize(
                StaticCodeAuthFlowFactory(
                    ScriptedAuthFlow {
                        browserOpens += 1
                        browserOpened.complete(Unit)
                        browserUntilCancelled()
                    }
                )
            )
            val service = AuthService(
                oidcRepository(
                    storage = AuthStorage(
                        tokenStore = RecordingTokenStore(),
                        settings = MapSettings().toSuspendSettings(),
                        json = Json { ignoreUnknownKeys = true }
                    ),
                    logger = logger
                )
            )
            advanceUntilIdle()

            val firstTap = launch { service.login() }
            // Auth storage reads run on another dispatcher, so wait for the browser rather than the test scheduler.
            browserOpened.await()
            val secondTap = async { runCatching { service.login() } }
            runCurrent()
            assertFalse(secondTap.isCompleted)

            firstTap.cancelAndJoin()

            assertIs<AuthenticationCancelledException>(secondTap.await().exceptionOrNull())
            assertEquals(1, browserOpens)
            assertIs<AuthState.Idle>(service.authState.value)
            assertEquals(emptyList<String>(), errors)
        }
}
