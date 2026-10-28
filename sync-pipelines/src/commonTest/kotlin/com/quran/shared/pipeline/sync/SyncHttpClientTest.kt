package com.quran.shared.pipeline.sync

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The sync client's timeouts: a connect and an idle (socket) timeout, which the platform engine applies, and no cap on
 * a request as a whole, so a large page on a slow network can finish.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncHttpClientTest {
    @Test
    fun `each request carries the connect and idle timeouts and no overall cap`() = runTest {
        var timeouts: HttpTimeoutConfig? = null
        val client = syncClient { request ->
            timeouts = request.getCapabilityOrNull(HttpTimeoutCapability)
            respondOk()
        }

        client.get("https://apis.quran.foundation/auth/v1/sync")

        val config = assertNotNull(timeouts)
        assertEquals(SyncClient.SYNC_CONNECT_TIMEOUT_MS, config.connectTimeoutMillis)
        assertEquals(SyncClient.SYNC_SOCKET_TIMEOUT_MS, config.socketTimeoutMillis, "the idle timeout")
        val overall = config.requestTimeoutMillis
        assertTrue(overall == null || overall == HttpTimeoutConfig.INFINITE_TIMEOUT_MS, "no overall cap: $overall")
    }

    /** A response that takes minutes in all (data still coming, as on a slow network) is not cut off. */
    @Test
    fun `a request that takes minutes still completes`() = runTest {
        val client = syncClient {
            delay(5.minutes)
            respondOk("page")
        }

        val body = client.get("https://apis.quran.foundation/auth/v1/sync").bodyAsText()

        assertEquals("page", body)
        assertEquals(5.minutes.inWholeMilliseconds, currentTime)
    }

    /** The production client on a mock engine that runs on the test's virtual clock. */
    private fun TestScope.syncClient(handler: MockRequestHandler) =
        SyncClient.createSyncHttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler(handler)
                }
            )
        )
}
