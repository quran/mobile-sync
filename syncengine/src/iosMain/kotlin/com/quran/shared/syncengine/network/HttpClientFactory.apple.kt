package com.quran.shared.syncengine.network

import co.touchlab.kermit.Logger as KermitLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger as KtorLogger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import platform.Foundation.NSProcessInfo

private const val HTTP_LOG_LEVEL_ENVIRONMENT_KEY = "MOBILE_SYNC_HTTP_LOG_LEVEL"
private val httpLogger = KermitLogger.withTag("KtorHTTP")

actual object HttpClientFactory {
    actual fun createHttpClient(): HttpClient {
        return HttpClient(Darwin) {
            install(ContentNegotiation) {
                json(Json { explicitNulls = false })
            }
            install(Logging) {
                logger = object : KtorLogger {
                    override fun log(message: String) {
                        httpLogger.d { message }
                    }
                }
                level = configuredHttpLogLevel()
                sanitizeHeader { header ->
                    header.equals(HttpHeaders.Authorization, ignoreCase = true) ||
                        header.equals(HttpHeaders.Cookie, ignoreCase = true) ||
                        header.equals(HttpHeaders.SetCookie, ignoreCase = true)
                }
            }
        }
    }
}

private fun configuredHttpLogLevel(): LogLevel {
    val configuredLevel = NSProcessInfo.processInfo.environment[HTTP_LOG_LEVEL_ENVIRONMENT_KEY] as? String
    return when (configuredLevel?.uppercase()) {
        "ALL" -> LogLevel.ALL
        "BODY" -> LogLevel.BODY
        "HEADERS" -> LogLevel.HEADERS
        "NONE" -> LogLevel.NONE
        else -> LogLevel.INFO
    }
}
