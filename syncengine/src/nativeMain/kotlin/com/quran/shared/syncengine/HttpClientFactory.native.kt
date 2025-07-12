package com.quran.shared.syncengine

import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*

actual object HttpClientFactory {
    actual fun createHttpClient(): HttpClient {
        return HttpClient(Darwin) {
            install(ContentNegotiation) {
                json()
            }
            install(Logging) {
                level = LogLevel.INFO
            }
        }
    }
} 