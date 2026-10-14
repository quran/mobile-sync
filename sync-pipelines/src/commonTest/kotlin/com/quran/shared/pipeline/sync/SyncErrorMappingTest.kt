package com.quran.shared.pipeline.sync

import com.quran.shared.pipeline.SyncError
import com.quran.shared.pipeline.SyncErrorKind
import com.quran.shared.syncv2.SyncHttpException
import com.quran.shared.syncv2.SyncResult
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/** [toSyncError]: how a failed run reaches the app through `syncStatus`. */
class SyncErrorMappingTest {

    @Test
    fun `http failures map to a kind by status`() {
        val kinds = listOf(401, 403, 408, 429, 500, 503, 400, 404, 409, 422).associateWith { status ->
            failed(SyncHttpException(status, code = null, mutationIndex = null, message = "")).toSyncError().kind
        }

        assertEquals(
            mapOf(
                401 to SyncErrorKind.AUTHENTICATION,
                403 to SyncErrorKind.AUTHENTICATION,
                408 to SyncErrorKind.SERVER,
                429 to SyncErrorKind.SERVER,
                500 to SyncErrorKind.SERVER,
                503 to SyncErrorKind.SERVER,
                400 to SyncErrorKind.REJECTED,
                404 to SyncErrorKind.REJECTED,
                409 to SyncErrorKind.REJECTED,
                422 to SyncErrorKind.REJECTED
            ),
            kinds
        )
    }

    @Test
    fun `an http failure keeps its status and the server's error code`() {
        val cause = SyncHttpException(409, "UniqueViolationError", mutationIndex = 0, message = "Collection 'Private' exists")

        assertEquals(
            SyncError(SyncErrorKind.REJECTED, 409, "UniqueViolationError", "The sync server rejected the request (HTTP 409)"),
            failed(cause).toSyncError()
        )
    }

    @Test
    fun `the message never carries the response body or the server's message`() {
        val cause = SyncHttpException(400, "ValidationError", mutationIndex = null, message = "Collection 'Private name' already exists")

        assertEquals("The sync server rejected the request (HTTP 400)", failed(cause).toSyncError().message)
    }

    @Test
    fun `a network error has no status even when it is wrapped`() {
        val network = SyncError(SyncErrorKind.NETWORK, null, null, "Could not reach the sync server")

        assertEquals(network, failed(IOException("Connection reset")).toSyncError())
        assertEquals(network, failed(IllegalStateException("Token refresh failed", IOException("Timeout"))).toSyncError())
    }

    @Test
    fun `other failures are unknown and name only the exception type or the run's reason`() {
        assertEquals(
            SyncError(SyncErrorKind.UNKNOWN, null, null, "Sync failed unexpectedly (IllegalStateException)"),
            failed(IllegalStateException("Private detail")).toSyncError()
        )
        assertEquals(
            SyncError(SyncErrorKind.UNKNOWN, null, null, "Sync failed unexpectedly (the head kept moving)"),
            failed(null, reason = "the head kept moving").toSyncError()
        )
    }

    private fun failed(cause: Throwable?, reason: String = "failed") =
        SyncResult.Failed(reason = reason, retryable = true, cause = cause)
}
