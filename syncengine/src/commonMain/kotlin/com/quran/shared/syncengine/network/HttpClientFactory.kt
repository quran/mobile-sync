package com.quran.shared.syncengine.network

import io.ktor.client.HttpClient

expect object HttpClientFactory {
    fun createHttpClient(): HttpClient
} 