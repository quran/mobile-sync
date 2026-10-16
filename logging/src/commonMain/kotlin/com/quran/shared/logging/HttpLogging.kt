package com.quran.shared.logging

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.LoggingFormat
import kotlin.native.HiddenFromObjC
import io.ktor.client.plugins.logging.Logger as KtorLogger

private const val FAILED_CALL_PREFIX = "<-- HTTP FAILED: "
private const val CANCELLATION = "CancellationException"

private val sensitiveHeaders = listOf("Authorization", "Cookie", "Set-Cookie", "x-auth-token")

/**
 * Logs this client's calls under [tag]: a summary line at info (warn when a call fails, unless it
 * was cancelled) and, when [MobileSyncLogging] allows debug, the whole entry at debug. [redact]
 * runs on everything logged. A failed call's summary names only the exception's class: its message
 * can hold the URL with its query (an `NSError` on iOS) or auth data.
 */
@HiddenFromObjC
fun HttpClientConfig<*>.installHttpLogging(tag: String, redact: (String) -> String = { it }) {
    install(Logging) {
        format = LoggingFormat.OkHttp
        level = if (MobileSyncLogging.detailedHttpLogs) LogLevel.ALL else LogLevel.INFO
        logger = HttpCallLogger(Logger.withTag(tag), redact)
        sanitizeHeader { header -> sensitiveHeaders.any { it.equals(header, ignoreCase = true) } }
    }
}

// Ktor's OkHttp format logs each request and response as one message whose first line is the summary,
// and a failed call as "<-- HTTP FAILED: $cause".
internal class HttpCallLogger(
    private val logger: Logger,
    private val redact: (String) -> String
) : KtorLogger {
    override fun log(message: String) {
        val text = redact(message)
        val firstLine = text.substringBefore('\n')
        val failed = firstLine.startsWith(FAILED_CALL_PREFIX)
        val summary = if (failed) failedCallSummary(firstLine) else firstLine
        if (failed && CANCELLATION !in firstLine) {
            logger.w { summary }
        } else {
            logger.i { summary }
        }
        if (summary != text) {
            logger.d { text }
        }
    }
}

/**
 * The prefix and the cause's class name, read from its `toString()`: `"<qualified name>: <message>"`, or the
 * qualified name alone. Only a text that is wholly a qualified class name before the first `": "` names a class;
 * anything else, such as a `toString()` overridden to print a token or a URL, logs as `Throwable`.
 */
private fun failedCallSummary(line: String): String {
    val cause = line.removePrefix(FAILED_CALL_PREFIX).substringBefore(": ")
    return FAILED_CALL_PREFIX + (QUALIFIED_CLASS_NAME.matchEntire(cause)?.groupValues?.get(1) ?: "Throwable")
}

private val QUALIFIED_CLASS_NAME = Regex("""(?:[a-z_][a-z0-9_]*\.)+(?:[A-Z][\w$]*\.)*([A-Z][\w$]*)""")
