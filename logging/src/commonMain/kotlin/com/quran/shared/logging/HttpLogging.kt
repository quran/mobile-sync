package com.quran.shared.logging

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.LoggingFormat
import kotlin.native.HiddenFromObjC
import io.ktor.client.plugins.logging.Logger as KtorLogger

private const val FAILED_CALL_PREFIX = "<-- HTTP FAILED"

private val sensitiveHeaders = listOf("Authorization", "Cookie", "Set-Cookie", "x-auth-token")

/**
 * Logs this client's calls under [tag]: a summary line at info (warn for a failed call) and, when
 * [MobileSyncLogging] allows debug, the whole entry at debug. [redact] runs on everything logged.
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

internal class HttpCallLogger(
    private val logger: Logger,
    private val redact: (String) -> String
) : KtorLogger {
    override fun log(message: String) {
        val text = redact(message)
        val summary = text.substringBefore('\n')
        if (summary.startsWith(FAILED_CALL_PREFIX)) {
            logger.w { summary }
        } else {
            logger.i { summary }
        }
        if (summary.length < text.length) {
            logger.d { text }
        }
    }
}
