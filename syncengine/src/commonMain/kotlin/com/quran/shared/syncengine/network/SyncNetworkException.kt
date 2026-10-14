package com.quran.shared.syncengine.network

import io.ktor.http.HttpStatusCode

/**
 * @param serverCode the server's stable error identifier, such as `UniqueViolationError`.
 */
class SyncNetworkException(
    val status: HttpStatusCode,
    val rawBody: String,
    val parsedMessage: String?,
    val serverCode: String? = null
) : Exception("HTTP request failed with status ${status.value}: ${parsedMessage ?: rawBody}")
