package com.quran.shared.syncengine

import com.quran.shared.syncengine.network.SyncNetworkException
import io.ktor.http.HttpStatusCode
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncErrorTest {

    @Test
    fun `http failures map to a kind by status`() {
        val kinds = listOf(401, 403, 408, 429, 500, 503, 400, 422).associateWith { code ->
            httpFailure(code).toSyncError().kind
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
                422 to SyncErrorKind.REJECTED
            ),
            kinds
        )
        assertEquals(422, httpFailure(422).toSyncError().httpStatus)
    }

    @Test
    fun `transport failures are network errors without a status`() {
        assertEquals(
            SyncError(SyncErrorKind.NETWORK, null, "Could not reach the sync server"),
            IOException("Connection reset").toSyncError()
        )
    }

    @Test
    fun `other failures are unknown and name only the exception type`() {
        assertEquals(
            SyncError(SyncErrorKind.UNKNOWN, null, "Sync failed unexpectedly (IllegalStateException)"),
            IllegalStateException("Sync head changed during pagination").toSyncError()
        )
    }

    @Test
    fun `message never includes the response body or server message`() {
        val error = SyncNetworkException(
            status = HttpStatusCode.BadRequest,
            rawBody = """{"message":"Collection 'Private name' already exists"}""",
            parsedMessage = "Collection 'Private name' already exists"
        ).toSyncError()

        assertEquals("The sync server rejected the request (HTTP 400)", error.message)
    }

    private fun httpFailure(code: Int) =
        SyncNetworkException(HttpStatusCode.fromValue(code), rawBody = "", parsedMessage = null)
}
