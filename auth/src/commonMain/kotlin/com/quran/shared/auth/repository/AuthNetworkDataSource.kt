package com.quran.shared.auth.repository

import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.model.UserInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.*

class AuthNetworkDataSource(
    private val authConfig: AuthConfig,
    private val httpClient: HttpClient
) {
    suspend fun fetchUserInfo(accessToken: String): UserInfo {
        return httpClient.get(authConfig.userinfoEndpoint) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("x-auth-token", accessToken)
            header("x-client-id", authConfig.clientId)
        }.body()
    }
}
