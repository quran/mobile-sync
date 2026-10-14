package com.quran.shared.syncv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `error.code` and `mutationIndex` must be read from the backend's shape and from the API gateway's wrapper. */
class SyncErrorParsingTest {
    @Test
    fun `reads the backend's own error shape`() {
        val error = parseSyncError(
            409,
            """{"success":false,"error":{"code":"UniqueViolationError","message":"dup","details":{"name":"DUPLICATE","mutationIndex":3}}}"""
        )

        assertEquals("UniqueViolationError", error.code)
        assertEquals(3, error.mutationIndex)
    }

    @Test
    fun `reads the API gateway's wrapped error shape`() {
        val error = parseSyncError(
            409,
            """{"message":"Invalid lastMutationAt","type":"error","success":false,"requestId":"r1",
               "details":{"success":false,"error":{"code":"OutOfSyncError","message":"Invalid lastMutationAt"}}}"""
        )

        assertEquals("OutOfSyncError", error.code)
        assertNull(error.mutationIndex)
    }

    @Test
    fun `reads the mutation index from the gateway's wrapped details`() {
        val error = parseSyncError(
            422,
            """{"message":"bad","type":"error","success":false,"requestId":"r1",
               "details":{"success":false,"error":{"code":"ValidationError","message":"bad","details":{"mutationIndex":1}}}}"""
        )

        assertEquals("ValidationError", error.code)
        assertEquals(1, error.mutationIndex)
    }

    @Test
    fun `a gateway-only error has a status and no code`() {
        val error = parseSyncError(429, """{"message":"Too Many Requests"}""")

        assertEquals(429, error.status)
        assertNull(error.code)
    }

    @Test
    fun `a body that is not JSON keeps the status`() {
        val error = parseSyncError(502, "<html>Bad Gateway</html>")

        assertEquals(502, error.status)
        assertNull(error.code)
    }
}
