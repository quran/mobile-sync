package com.quran.shared.auth.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse

@Serializable
data class TokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("id_token")
    val idToken: String? = null,
    @SerialName("expires_in")
    val expiresIn: Long,
    @SerialName("token_type")
    val tokenType: String
) {
    companion object {
        fun fromOidc(response: AccessTokenResponse): TokenResponse {
            return TokenResponse(
                accessToken = response.access_token,
                refreshToken = response.refresh_token,
                idToken = response.id_token,
                expiresIn = response.expires_in?.toLong() ?: 3600L,
                tokenType = response.token_type ?: "Bearer"
            )
        }
    }
}
