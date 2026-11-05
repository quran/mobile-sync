package com.quran.shared.logging

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.LoggingFormat
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpSendPipeline
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import kotlin.coroutines.cancellation.CancellationException
import kotlin.native.HiddenFromObjC
import kotlin.time.Duration
import kotlin.time.TimeSource
import io.ktor.client.plugins.logging.Logger as KtorLogger

private const val FAILED_CALL_PREFIX = "<-- HTTP FAILED"

// How Ktor's OkHttp format (3.6.0) logs a failed call: the cause alone, without the request.
private const val KTOR_FAILED_CALL_PREFIX = "$FAILED_CALL_PREFIX: "

private val sensitiveHeaders = listOf("Authorization", "Cookie", "Set-Cookie", "x-auth-token")

/**
 * Logs this client's calls under [tag]: a summary line at info (warn when a call fails, unless it
 * was cancelled) and, when [MobileSyncLogging] allows debug, the whole entry at debug. A failed
 * call's summary names its request, such as `<-- HTTP FAILED GET https://… (60012ms): <cause>`.
 * [redact] runs on everything logged.
 */
@HiddenFromObjC
fun HttpClientConfig<*>.installHttpLogging(tag: String, redact: (String) -> String = { it }) {
    val callLogger = HttpCallLogger(Logger.withTag(tag), redact)
    install(Logging) {
        format = LoggingFormat.OkHttp
        level = if (MobileSyncLogging.detailedHttpLogs) LogLevel.ALL else LogLevel.INFO
        logger = callLogger
        sanitizeHeader { header -> sensitiveHeaders.any { it.equals(header, ignoreCase = true) } }
    }
    // Installed after Logging, so it runs inside Logging's interceptor and sees every failure Logging sees.
    install(createFailedCallLogging(callLogger))
}

// Logs a failed call with its request. Ktor's own failure line has only the cause, which can't be tied to its
// request when calls overlap, and a multi-line cause (Darwin's NSError) hides the URL from the summary.
private fun createFailedCallLogging(callLogger: HttpCallLogger) = createClientPlugin("FailedCallLogging") {
    client.sendPipeline.intercept(HttpSendPipeline.Monitoring) {
        val start = TimeSource.Monotonic.markNow()
        try {
            proceed()
        } catch (cause: Throwable) {
            callLogger.logFailure(context, cause, start.elapsedNow())
            throw cause
        }
    }
}

// Ktor's OkHttp format logs each request and response as one message whose first line is the summary.
internal class HttpCallLogger(
    private val logger: Logger,
    private val redact: (String) -> String
) : KtorLogger {
    override fun log(message: String) {
        // logFailure logs the failure with its request instead.
        if (message.startsWith(KTOR_FAILED_CALL_PREFIX)) return
        log(message, warn = false)
    }

    fun logFailure(request: HttpRequestBuilder, cause: Throwable, duration: Duration) {
        val url = URLBuilder().takeFrom(request.url).build()
        val message = "$FAILED_CALL_PREFIX ${request.method.value} $url (${duration.inWholeMilliseconds}ms): $cause"
        log(message, warn = cause !is CancellationException)
    }

    private fun log(message: String, warn: Boolean) {
        val text = redact(message)
        val summary = text.substringBefore('\n')
        if (warn) {
            logger.w { summary }
        } else {
            logger.i { summary }
        }
        if (summary.length < text.length) {
            logger.d { text }
        }
    }
}
