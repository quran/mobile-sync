package com.quran.shared.syncengine.network

import io.ktor.http.HttpStatusCode

class SyncNetworkException(
    val status: HttpStatusCode,
    val rawBody: String,
    val parsedMessage: String?
) : Exception("HTTP request failed with status ${status.value}: ${parsedMessage ?: rawBody}")
