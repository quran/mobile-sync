package com.quran.shared.auth.di

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The auth client gives up on a token request that never answers instead of hanging sign-in or refresh. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthHttpClientTest {
    @Test
    fun `a request that never answers times out after the request timeout`() = runTest {
        val client = authClient { awaitCancellation() }

        assertFailsWith<HttpRequestTimeoutException> {
            client.get("https://prelive-oauth2.quran.foundation/oauth2/token")
        }

        assertEquals(AuthModule.AUTH_REQUEST_TIMEOUT_MS, currentTime)
    }

    /** The production client on a mock engine that runs on the test's virtual clock. */
    private fun TestScope.authClient(handler: MockRequestHandler) =
        AuthModule.createAuthHttpClient(
            json = Json { ignoreUnknownKeys = true },
            engine = MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler(handler)
                }
            )
        )
}
