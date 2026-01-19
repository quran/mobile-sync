package com.quran.shared.auth.repository

import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.model.UserInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.*
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import kotlinx.serialization.json.Json

class AuthNetworkDataSource(
    private val authConfig: AuthConfig
) {
    private val httpClient = HttpClient {
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    println("HTTP Client: $message")
                }
            }
            level = LogLevel.ALL
        }
        install(ContentNegotiation) {
            register(
                contentTypeToSend = ContentType.Application.Json,
                converter = KotlinxSerializationConverter(
                    Json {
                        explicitNulls = false
                        ignoreUnknownKeys = true
                    }
                ),
                contentTypeMatcher = object : ContentTypeMatcher {
                    override fun contains(contentType: ContentType): Boolean {
                        return true
                    }
                }
            ) {}
        }
    }

    suspend fun fetchUserInfo(accessToken: String): UserInfo {
        return httpClient.get(authConfig.userinfoEndpoint) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("x-auth-token", accessToken)
            header("x-client-id", authConfig.clientId)
        }.body()
    }
}
