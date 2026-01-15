package com.quran.shared.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.encodeBase64
import io.ktor.util.generateNonce
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.kotlincrypto.hash.sha2.SHA256
import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import org.publicvalue.multiplatform.oidc.types.CodeChallengeMethod
import kotlin.time.Clock

/**
 * Manages OAuth authentication with Quran.com using Quran Foundation's OAuth2 endpoints.
 *
 * Implements OAuth 2.0 with PKCE (RFC 7636) for secure public client authentication.
 * Platform-agnostic implementation for Kotlin Multiplatform.
 *
 * Reference: https://api-docs.quran.foundation/docs/category/oauth2_apis
 */
class AuthenticationManager(
    private val usePreProduction: Boolean = true
) {
    // OAuth endpoints from Quran Foundation
    private val baseUrl = if (usePreProduction) {
        "https://prelive-oauth2.quran.foundation"
    } else {
        "https://oauth2.quran.foundation"
    }

    private val authorizationEndpoint = "$baseUrl/oauth2/auth"
    private val tokenEndpoint = "$baseUrl/oauth2/token"
    private val revokeEndpoint = "$baseUrl/oauth2/revoke"

    // OAuth application credentials
    private val clientId = "YOUR_CLIENT_ID_HERE"
    private val clientSecret = null
    // Mobile redirect URI - must match the deep link scheme configured in the app
    private val redirectUri = "com.quran.oauth://callback"

    // Scopes requested from OAuth server
    private val requestedScopes = listOf(
        "openid",
        "offline_access",
        "content"
    )

    // HTTP client for making token requests
    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    // Internal OIDC client - not exposed to consumers
    private val oidcClient = OpenIdConnectClient(
        block = {
            endpoints {
                authorizationEndpoint = this@AuthenticationManager.authorizationEndpoint
                tokenEndpoint = this@AuthenticationManager.tokenEndpoint
            }
            
            clientId = this@AuthenticationManager.clientId
            clientSecret = this@AuthenticationManager.clientSecret // PKCE doesn't use client secret for public clients
            scope = requestedScopes.joinToString(" ")
            redirectUri = this@AuthenticationManager.redirectUri
            codeChallengeMethod = CodeChallengeMethod.S256
        }
    )

    /**
     * Builds the OAuth2 authorization URL for initiating login flow.
     *
     * Implements PKCE (Proof Key for Code Exchange) for enhanced security:
     * - Generates code_challenge from code_verifier
     * - Includes state parameter to prevent CSRF attacks
     *
     * @param codeVerifier PKCE code verifier (must be stored for token exchange)
     * @param state Random state value for CSRF protection (must be validated in redirect)
     * @return Authorization URL to open in browser
     */
    fun buildAuthorizationUrl(
        codeVerifier: String,
        state: String
    ): String {
        // Calculate code challenge (SHA-256 hash of code verifier)
        val codeChallenge = calculateSHA256(codeVerifier)

        // Build authorization URL with PKCE parameters
        val params = mapOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to requestedScopes.joinToString(" "),
            "state" to state,
            "nonce" to generateNonce(),
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256"
        )

        val queryString = params.entries.joinToString("&") { (key, value) ->
            "$key=${urlEncode(value)}"
        }

        return "$authorizationEndpoint?$queryString"
    }

    /**
     * Exchanges OAuth2 authorization code for access and refresh tokens.
     *
     * Implements PKCE token exchange by including the code_verifier with the request.
     *
     * @param code Authorization code from OAuth redirect
     * @param codeVerifier PKCE code verifier (must match the one used for authorization)
     * @return TokenResponse containing access_token and optional refresh_token
     * @throws IllegalArgumentException if code or verifier is empty
     * @throws Exception if token exchange fails
     */
    suspend fun exchangeCodeForToken(
        code: String,
        codeVerifier: String
    ): TokenResponse {
        require(code.isNotEmpty()) { "Authorization code cannot be empty" }
        require(codeVerifier.isNotEmpty()) { "Code verifier cannot be empty" }

        return try {
            // Make POST request to token endpoint
            val response: HttpResponse = httpClient.post(tokenEndpoint) {
                contentType(ContentType.Application.FormUrlEncoded)
                // Send form-encoded body with token request parameters
                val body = "grant_type=authorization_code" +
                        "&code=$code" +
                        "&client_id=$clientId" +
                        "&redirect_uri=${urlEncode(redirectUri)}" +
                        "&code_verifier=$codeVerifier"
                setBody(body)
            }

            // Parse response as JSON
            val responseBody: String = response.body()
            val jsonObject = Json.parseToJsonElement(responseBody).jsonObject

            // Check for error response
            if (jsonObject.containsKey("error")) {
                val error = jsonObject["error"]?.jsonPrimitive?.content ?: "Unknown error"
                val errorDescription = jsonObject["error_description"]?.jsonPrimitive?.content
                    ?: "No description provided"
                throw Exception("Token exchange failed: $error - $errorDescription")
            }

            // Extract tokens from successful response
            val accessToken = jsonObject["access_token"]?.jsonPrimitive?.content
                ?: throw Exception("No access_token in response")

            val refreshToken = jsonObject["refresh_token"]?.jsonPrimitive?.content

            val expiresIn = jsonObject["expires_in"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: 3600L

            TokenResponse(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresIn = expiresIn,
                tokenType = jsonObject["token_type"]?.jsonPrimitive?.content ?: "Bearer"
            )
        } catch (e: Exception) {
            throw Exception("Failed to exchange authorization code for token: ${e.message}", e)
        }
    }

    /**
     * Refreshes an expired access token using a refresh token.
     *
     * @param refreshToken Previously obtained refresh token
     * @return New TokenResponse with updated access_token
     * @throws IllegalArgumentException if refresh token is empty
     * @throws Exception if token refresh fails
     */
    suspend fun refreshToken(refreshToken: String): TokenResponse {
        require(refreshToken.isNotEmpty()) { "Refresh token cannot be empty" }

        return try {
            // Make POST request to token endpoint for refresh
            val response: HttpResponse = httpClient.post(tokenEndpoint) {
                contentType(ContentType.Application.FormUrlEncoded)
                val body = "grant_type=refresh_token" +
                        "&refresh_token=$refreshToken" +
                        "&client_id=$clientId"
                setBody(body)
            }

            // Parse response
            val responseBody: String = response.body()
            val jsonObject = Json.parseToJsonElement(responseBody).jsonObject

            // Check for error
            if (jsonObject.containsKey("error")) {
                val error = jsonObject["error"]?.jsonPrimitive?.content ?: "Unknown error"
                throw Exception("Token refresh failed: $error")
            }

            // Extract new tokens
            val accessToken = jsonObject["access_token"]?.jsonPrimitive?.content
                ?: throw Exception("No access_token in refresh response")

            val newRefreshToken = jsonObject["refresh_token"]?.jsonPrimitive?.content
                ?: refreshToken // Reuse old refresh token if not provided

            val expiresIn = jsonObject["expires_in"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: 3600L

            TokenResponse(
                accessToken = accessToken,
                refreshToken = newRefreshToken,
                expiresIn = expiresIn,
                tokenType = jsonObject["token_type"]?.jsonPrimitive?.content ?: "Bearer"
            )
        } catch (e: Exception) {
            throw Exception("Failed to refresh token: ${e.message}", e)
        }
    }

    /**
     * Revokes an access or refresh token (logs out user).
     *
     * @param token The token to revoke (access_token or refresh_token)
     * @param tokenTypeHint Optional hint: "access_token" or "refresh_token"
     * @return true if revocation succeeded, false otherwise
     */
    suspend fun revokeToken(
        token: String,
        tokenTypeHint: String? = null
    ): Boolean {
        return try {
            require(token.isNotEmpty()) { "Token cannot be empty" }

            val response: HttpResponse = httpClient.post(revokeEndpoint) {
                contentType(ContentType.Application.FormUrlEncoded)
                val body = buildString {
                    append("token=$token")
                    append("&client_id=$clientId")
                    if (tokenTypeHint != null) {
                        append("&token_type_hint=$tokenTypeHint")
                    }
                }
                setBody(body)
            }

            // HTTP 200 means success, 204 means already revoked
            response.status.value in listOf(200, 204)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Validates if a token is still valid (not expired).
     *
     * @param expirationTime Token expiration timestamp (milliseconds since epoch)
     * @return true if token is still valid, false if expired
     */
    fun isTokenValid(expirationTime: Long): Boolean {
        // Add 60 second buffer to refresh before actual expiration
        return currentTimeMillis() < (expirationTime - 60_000)
    }

    // ========================= Helper Methods =========================

    /**
     * Calculates SHA-256 hash of input string and returns Base64URL encoded result.
     * Platform-agnostic implementation using platform-specific functions.
     *
     * @param input String to hash
     * @return Base64URL encoded SHA-256 hash (without padding)
     */
    private fun calculateSHA256(input: String): String {
        val bytes = input.encodeToByteArray()
        val digest = sha256(bytes)
        return base64UrlEncode(digest)
    }

    /**
     * URL encodes a string for use in query parameters.
     * Platform-agnostic implementation using pure Kotlin.
     *
     * @param value String to encode
     * @return URL-encoded string
     */
    private fun urlEncode(value: String): String {
        return value.toCharArray().map { char ->
            when (char) {
                in 'A'..'Z', in 'a'..'z', in '0'..'9', '-', '.', '_', '~' -> char.toString()
                ' ' -> "%20"
                else -> {
                    val bytes = char.toString().encodeToByteArray()
                    bytes.joinToString("") { "%${it.toUByte().toString(16).padStart(2, '0').uppercase()}" }
                }
            }
        }.joinToString("")
    }
}

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

fun sha256(data: ByteArray): ByteArray {
    val digest = SHA256()
    digest.update(data)
    return digest.digest()
}
fun base64UrlEncode(data: ByteArray): String {
    val base64 = data.encodeBase64()
    return base64.replace("+", "-")
        .replace("/", "_")
        .replace("=", "")
}

fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()


