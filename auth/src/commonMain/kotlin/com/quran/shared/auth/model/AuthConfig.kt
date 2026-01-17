// src/commonMain/kotlin/com/quran/shared/auth/model/AuthConfig.kt
package com.quran.shared.auth.model


data class AuthConfig(
    val usePreProduction: Boolean = false,
    val clientId: String,
    val clientSecret: String? = null,
    val redirectUri: String = "com.quran.oauth://callback",
    val scopes: List<String> = listOf("openid", "offline_access", "content")
) {
    val baseUrl: String = if (usePreProduction) {
        "https://prelive-oauth2.quran.foundation"
    } else {
        "https://oauth2.quran.foundation"
    }

    val authorizationEndpoint = "$baseUrl/oauth2/auth"
    val tokenEndpoint = "$baseUrl/oauth2/token"
    val revokeEndpoint = "$baseUrl/oauth2/revoke"
}