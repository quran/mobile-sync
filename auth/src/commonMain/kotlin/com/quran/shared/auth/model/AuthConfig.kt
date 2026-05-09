package com.quran.shared.auth.model

import com.quran.shared.auth.BuildKonfig

enum class AuthEnvironment(
    val baseUrl: String,
    val enableVerboseLogging: Boolean
) {
    PRELIVE(
        baseUrl = "https://prelive-oauth2.quran.foundation",
        enableVerboseLogging = true
    ),
    PRODUCTION(
        baseUrl = "https://oauth2.quran.foundation",
        enableVerboseLogging = false
    )
}

data class AuthConfig(
    val environment: AuthEnvironment = defaultAuthEnvironment(),
    val clientId: String,
    val clientSecret: String? = null,
    val redirectUri: String = "com.quran.oauth://callback",
    val postLogoutRedirectUri: String = "com.quran.oauth://callback",
    val scopes: List<String> = listOf("openid", "offline_access", "content", "user", "bookmark", "sync", "collection", "reading_session", "preference", "note")
) {
    init {
        require(clientId.isNotBlank()) {
            "Auth clientId must be provided by the consuming app."
        }
        require(clientSecret == null || clientSecret.isNotBlank()) {
            "Auth clientSecret must be null or non-blank."
        }
    }

    val baseUrl: String = environment.baseUrl
    val authorizationEndpoint = "$baseUrl/oauth2/auth"
    val tokenEndpoint = "$baseUrl/oauth2/token"
    val userinfoEndpoint = "$baseUrl/userinfo"
    val endSessionEndpoint = "$baseUrl/oauth2/sessions/logout"
    val revocationEndpoint = "$baseUrl/oauth2/revoke"
}

fun defaultAuthEnvironment(): AuthEnvironment {
    return if (BuildKonfig.IS_DEBUG) AuthEnvironment.PRELIVE else AuthEnvironment.PRODUCTION
}
