package com.quran.shared.pipeline.sync

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter
import com.quran.shared.logging.MobileSyncLogSeverity
import com.quran.shared.logging.MobileSyncLogging
import com.quran.shared.pipeline.SyncError
import com.quran.shared.pipeline.SyncErrorKind
import com.quran.shared.pipeline.inMemoryMobileSyncDatabase
import com.quran.shared.syncv2.SyncApi
import com.quran.shared.syncv2.SyncEngineV2
import com.quran.shared.syncv2.types.syncTypes
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.publicvalue.multiplatform.oidc.OpenIdConnectException
import org.publicvalue.multiplatform.oidc.types.remote.ErrorResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** A run's token refresh can fail with tokens, codes, URLs and the user's email in its messages: none reach a warning. */
class SyncClientLoggingTest {
    private val warnings = mutableListOf<String>()

    @AfterTest
    fun restoreLogs() {
        Logger.setLogWriters(platformLogWriter())
        Logger.setMinSeverity(Severity.Verbose)
    }

    @Test
    fun `a refused token refresh before a run warns with its summary only`() = runBlocking {
        MobileSyncLogging.configure({ _, _, message -> synchronized(warnings) { warnings += message } }, MobileSyncLogSeverity.WARN)
        val code = "secret-auth-code"
        val refused = OpenIdConnectException.UnsuccessfulTokenRequest(
            message = "Refresh failed: token secret-access-token for reader@example.com",
            statusCode = HttpStatusCode.BadRequest,
            errorResponse = ErrorResponse(ErrorResponse.Error.invalid_grant, "revoked", "https://a.example.com/cb?code=$code&state=s", null),
            cause = IllegalStateException("POST grant_type=refresh_token&refresh_token=secret-refresh-token")
        )
        val database = inMemoryMobileSyncDatabase().synced
        val httpClient = HttpClient(OkHttp)
        val failed = CompletableDeferred<SyncError>()
        val client = SyncClient(
            SyncEngineV2(database, SyncApi(httpClient, "https://sync.example.com") { error("no request expected") }, syncTypes(database), "test"),
            account = object : SyncAccount {
                override suspend fun authHeaders(): Map<String, String> = throw refused

                override suspend fun accountId(): String = "account"

                override suspend fun forceTokenRefresh(): Boolean = error("no 401 expected")
            },
            gate = object : SyncLifecycleGate {},
            observer = object : SyncRunObserver {
                override fun syncFailed(error: SyncError) {
                    failed.complete(error)
                }
            },
            SchedulerTimings(1.hours, 1.hours, 1.hours, FailureRetryingConfig(1.seconds, 1.0, maximumRetries = 0)),
            Dispatchers.Default,
            httpClient = httpClient
        )

        val error = try {
            client.triggerSyncImmediately()
            withTimeout(10.seconds) { failed.await() }
        } finally {
            client.close()
        }

        assertEquals(SyncErrorKind.UNKNOWN, error.kind, "classified by the failure itself")
        assertEquals(
            listOf("Sync failed: UnsuccessfulTokenRequest(status=400, error=invalid_grant) <- IllegalStateException"),
            synchronized(warnings) { warnings.toList() }
        )
    }
}
