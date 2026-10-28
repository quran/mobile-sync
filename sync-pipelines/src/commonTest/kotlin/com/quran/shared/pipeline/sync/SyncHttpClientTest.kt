package com.quran.shared.pipeline.sync

import com.quran.shared.pipeline.SyncErrorKind
import com.quran.shared.syncv2.SyncResult
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The sync client gives up on a request that never answers instead of holding the run, and reports a network error. */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncHttpClientTest {
    @Test
    fun `a request that never answers times out after the request timeout as a network error`() = runTest {
        val client = syncClient { awaitCancellation() }

        val timeout = assertFailsWith<HttpRequestTimeoutException> {
            client.get("https://apis.quran.foundation/auth/v1/sync")
        }

        assertEquals(SyncClient.SYNC_REQUEST_TIMEOUT_MS, currentTime)
        val failed = SyncResult.Failed(timeout.toString(), retryable = true, cause = timeout)
        assertEquals(SyncErrorKind.NETWORK, failed.toSyncError().kind)
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
