package com.quran.shared.syncengine.network

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncRequestLogContextTest {

    @Test
    fun `format associates request id and attempt with message`() {
        val context = SyncRequestLogContext(
            requestId = "request-a",
            attempt = 3
        )

        assertEquals(
            "[requestId=request-a attempt=3] HTTP response status: 422",
            context.format("HTTP response status: 422")
        )
    }
}
