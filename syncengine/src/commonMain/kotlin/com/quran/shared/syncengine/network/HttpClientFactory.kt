package com.quran.shared.syncengine.network

import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*

expect object HttpClientFactory {
    fun createHttpClient(): HttpClient
} 