package com.quran.shared.syncv2

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter
import com.quran.shared.logging.MobileSyncLogSeverity
import com.quran.shared.logging.MobileSyncLogging
import com.quran.shared.syncv2.fake.FakeSyncServer
import com.quran.shared.syncv2.fake.HttpError
import com.quran.shared.syncv2.types.syncTypes
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A server's error message can echo user content, and a request-header failure can hold auth data: neither warns. */
class FailureLoggingTest {
    private data class Entry(val severity: MobileSyncLogSeverity, val message: String)

    private val entries = mutableListOf<Entry>()

    @AfterTest
    fun restoreLogs() {
        MobileSyncLogging.configure({ _, _, _ -> }, MobileSyncLogSeverity.INFO) // detailed HTTP logs off again
        Logger.setLogWriters(platformLogWriter())
        Logger.setMinSeverity(Severity.Verbose)
    }

    @Test
    fun `failures warn and report by status and code and class and keep the server message at debug`() =
        FakeSyncServer().use { server ->
            MobileSyncLogging.configure({ severity, _, message -> entries += Entry(severity, message) }, MobileSyncLogSeverity.DEBUG)
            Device(server).use { device ->
                device.collections.addCollection("Study", 1_760_000_000_000L)
                server.rejectMutation = { HttpError(403, "ForbiddenError", "$EMAIL may not write") }

                val forbidden = assertIs<SyncResult.Failed>(device.sync())

                assertEquals("request forbidden: HTTP 403 ForbiddenError", forbidden.reason)
                assertTrue(entries.any { it.severity == MobileSyncLogSeverity.WARN && it.message == "POST of 1 failed: HTTP 403 ForbiddenError" })
                assertTrue(entries.any { it.severity == MobileSyncLogSeverity.DEBUG && EMAIL in it.message }, "kept for debugging")
            }
            val headersFailure = IllegalStateException(
                "refresh failed for $EMAIL: grant_type=refresh_token&refresh_token=$TOKEN",
                IllegalArgumentException("GET https://auth.example.com/callback?code=$TOKEN&state=$TOKEN")
            )
            val database = inMemoryDatabase()
            val api = SyncApi(HttpClient(OkHttp), server.url) { throw headersFailure }

            val failed = assertIs<SyncResult.Failed>(runBlocking { SyncEngineV2(database, api, syncTypes(database), "1").sync(Device.ACCOUNT) })

            assertEquals("IllegalStateException <- IllegalArgumentException", failed.reason)
            assertEquals(headersFailure, failed.cause, "classified by the failure itself")
            val warnings = entries.filter { it.severity >= MobileSyncLogSeverity.WARN }
            assertTrue(Entry(MobileSyncLogSeverity.WARN, "Sync failed: ${failed.reason}") in warnings, "$warnings")
            warnings.forEach { warning -> listOf(EMAIL, TOKEN).forEach { assertFalse(it in warning.message, "$warning") } }
        }

    private companion object {
        const val EMAIL = "reader@example.com"
        const val TOKEN = "secret-token-123"
    }
}
