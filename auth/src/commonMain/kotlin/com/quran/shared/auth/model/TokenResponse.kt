package com.quran.shared.auth.model

/**
 * Response from OAuth2 token endpoint.
 *
 * Reference: https://api-docs.quran.foundation/docs/category/oauth2_apis
 */
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Long,
    val tokenType: String = "Bearer"
)