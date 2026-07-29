package com.quran.shared.syncengine.network

import kotlin.uuid.Uuid

internal data class SyncRequestLogContext(
    val requestId: String,
    val attempt: Int
) {
    fun format(message: String): String =
        "[requestId=$requestId attempt=$attempt] $message"

    companion object {
        fun create(attempt: Int): SyncRequestLogContext =
            SyncRequestLogContext(
                requestId = Uuid.random().toString(),
                attempt = attempt
            )
    }
}
