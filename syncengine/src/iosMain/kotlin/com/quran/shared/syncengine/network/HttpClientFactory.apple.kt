package com.quran.shared.syncengine.network

import com.quran.shared.logging.installHttpLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json

actual object HttpClientFactory {
    actual fun createHttpClient(): HttpClient {
        return HttpClient(Darwin) {
            install(ContentNegotiation) {
                json(
                    Json {
                        explicitNulls = false
                        ignoreUnknownKeys = true
                    }
                )
            }
            installHttpLogging(tag = "SyncHttp")
        }
    }
}
