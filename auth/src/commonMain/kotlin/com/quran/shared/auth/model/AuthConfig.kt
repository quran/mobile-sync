package com.quran.shared.auth.model

data class AuthConfig(
    val usePreProduction: Boolean = false,
    val clientId: String,
    val clientSecret: String? = null,
    val redirectUri: String = "com.quran.oauth://callback",
    val postLogoutRedirectUri: String = "com.quran.oauth://callback",
    val scopes: List<String> = listOf("openid", "offline_access", "content", "user", "bookmark", "sync", "collection", "reading_session", "preference")
) {
    val baseUrl: String = if (usePreProduction) {
        "https://prelive-oauth2.quran.foundation"
    } else {
        "https://oauth2.quran.foundation"
    }

    val authorizationEndpoint = "$baseUrl/oauth2/auth"
    val tokenEndpoint = "$baseUrl/oauth2/token"
    val userinfoEndpoint = "$baseUrl/userinfo"
    val endSessionEndpoint = "$baseUrl/oauth2/sessions/logout"
    val revocationEndpoint = "$baseUrl/oauth2/revoke"
}