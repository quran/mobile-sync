package com.quran.shared.syncengine.network

import io.ktor.http.HttpStatusCode

class SyncNetworkException(
    status: HttpStatusCode,
    rawBody: String,
    parsedMessage: String?
) : Exception("HTTP request failed with status ${status.value}: ${parsedMessage ?: rawBody}")
