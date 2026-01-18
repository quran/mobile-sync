package com.quran.shared.auth.repository

import com.quran.shared.auth.di.AuthFlowFactoryProvider
import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.persistence.AuthStorage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.*
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import io.ktor.util.decodeBase64String
import io.ktor.util.encodeBase64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.publicvalue.multiplatform.oidc.DefaultOpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectClientConfig
import org.publicvalue.multiplatform.oidc.types.CodeChallengeMethod
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse

/**
 * AuthRepository implementation that uses the OIDC library's CodeAuthFlow.
 * This delegates browser launching and redirect handling to the library.
 * 
 * Prerequisites:
 * - On Android: Call AuthFlowFactoryProvider.initialize() with AndroidCodeAuthFlowFactory in MainActivity
 * - On iOS: Call AuthFlowFactoryProvider.initialize() with IosCodeAuthFlowFactory at app startup
 */
class OidcAuthRepository(
    private val authConfig: AuthConfig,
    private val authStorage: AuthStorage
) : AuthRepository {

    private val oidcClient = DefaultOpenIdConnectClient(
        httpClient = HttpClient {
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        println("HTTP Client: $message")
                    }
                }
                level = LogLevel.ALL
            }
            install(ContentNegotiation) {
                // register custom type matcher to support broken IDPs that don't send correct content-type
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
        },
        config = OpenIdConnectClientConfig().apply {
            endpoints {
                authorizationEndpoint = authConfig.authorizationEndpoint
                tokenEndpoint = authConfig.tokenEndpoint
            }
            clientId = authConfig.clientId
            // We set clientSecret to null here to prevent the library from sending it
            // in the POST body (client_secret_post), because the server requires client_secret_basic.
            clientSecret = null
            scope = authConfig.scopes.joinToString(" ")
            redirectUri = authConfig.redirectUri
            codeChallengeMethod = CodeChallengeMethod.S256
            disableNonce = true
        }
    )

    /**
     * Custom configuration for token exchange and refresh requests.
     * Manually adds the Authorization Basic header as required by the server.
     */
    private val configureTokenExchange: HttpRequestBuilder.() -> Unit = {
        authConfig.clientSecret?.let { secret ->
            val clientId = authConfig.clientId
            val auth = "$clientId:$secret".encodeToByteArray().encodeBase64()
            println("OidcAuthRepository: Adding Basic Auth header for client ID: $clientId")
            header(HttpHeaders.Authorization, "Basic $auth")
        } ?: println("OidcAuthRepository: No client secret found, skipping Basic Auth header.")
    }

    /**
     * Performs the complete OAuth login flow using the OIDC library's CodeAuthFlow.
     * This handles browser launching, waiting for redirect, and token exchange all internally.
     * 
     * @throws Exception if authentication fails or is cancelled
     * @throws IllegalStateException if CodeAuthFlowFactory not initialized
     */
    override suspend fun login() {
        if (!AuthFlowFactoryProvider.isInitialized()) {
            throw IllegalStateException(
                "CodeAuthFlowFactory not initialized. " +
                "On Android: call AuthFlowFactoryProvider.initialize() in MainActivity. " +
                "On iOS: call AuthFlowFactoryProvider.doInitialize() at app startup."
            )
        }
        
        val authFlow = AuthFlowFactoryProvider.factory.createAuthFlow(oidcClient)
        val tokenResponse = authFlow.getAccessToken(
            configureTokenExchange = configureTokenExchange
        )
        
        storeAccessTokenResponse(tokenResponse)
    }

    /**
     * Checks if login can be continued (e.g., after app restart during auth).
     */
    suspend fun canContinueLogin(): Boolean {
        if (!AuthFlowFactoryProvider.isInitialized()) return false
        val authFlow = AuthFlowFactoryProvider.factory.createAuthFlow(oidcClient)
        return authFlow.canContinueLogin()
    }

    /**
     * Continues a previously started login if the app was killed during auth.
     * Call this on app resume to complete any pending authentication.
     */
    suspend fun continueLogin() {
        if (!AuthFlowFactoryProvider.isInitialized()) return
        val authFlow = AuthFlowFactoryProvider.factory.createAuthFlow(oidcClient)
        if (authFlow.canContinueLogin()) {
            val tokenResponse = authFlow.continueLogin(
                configureTokenExchange = configureTokenExchange
            )
            storeAccessTokenResponse(tokenResponse)
        }
    }

    private fun storeAccessTokenResponse(response: AccessTokenResponse) {
        val tokenResponse = TokenResponse(
            accessToken = response.access_token,
            refreshToken = response.refresh_token,
            idToken = response.id_token,
            expiresIn = response.expires_in?.toLong() ?: 3600L,
            tokenType = response.token_type ?: "Bearer"
        )
        authStorage.storeTokens(tokenResponse)
    }

    override suspend fun refreshTokensIfNeeded(): Boolean {
        return try {
            val refreshToken = authStorage.retrieveStoredRefreshToken() ?: return false
            val expirationTime = authStorage.retrieveTokenExpiration()

            // Check if token is expired (with 60s buffer)
            val currentTime = com.quran.shared.auth.utils.currentTimeMillis()
            if (currentTime >= expirationTime - 60_000) {
                val response = oidcClient.refreshToken(
                    refreshToken = refreshToken,
                    configure = configureTokenExchange
                )
                storeAccessTokenResponse(response)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun logout() {
        try {
            val idToken = authStorage.retrieveStoredIdToken()
            
            // Try end session if factory is available
            if (AuthFlowFactoryProvider.isInitialized() && idToken != null) {
                try {
                    val endSessionFlow = AuthFlowFactoryProvider.factory.createEndSessionFlow(oidcClient)
                    endSessionFlow.endSession(idToken)
                } catch (e: Exception) {
                    // End session might not be supported, just clear tokens
                    println("End session failed: ${e.message}")
                }
            }
        } finally {
            authStorage.clearAllTokens()
        }
    }

    override fun getAccessToken(): String? = authStorage.retrieveStoredAccessToken()

    override fun isLoggedIn(): Boolean = authStorage.retrieveStoredAccessToken() != null

    override fun getCurrentUser(): UserInfo? {
        val idToken = authStorage.retrieveStoredIdToken()
        val accessToken = authStorage.retrieveStoredAccessToken()
        
        return try {
            // Try parsing ID Token first (OIDC)
            if (idToken != null) {
                parseUserInfoFromToken(idToken)
            } else if (accessToken != null) {
                // Fallback to Access Token if it's a JWT (Plain OAuth2)
                parseUserInfoFromToken(accessToken)
            } else {
                null
            }
        } catch (e: Exception) {
            println("OidcAuthRepository: Failed to parse user info: ${e.message}")
            null
        }
    }

    private fun parseUserInfoFromToken(token: String): UserInfo {
        val parts = token.split(".")
        if (parts.size < 2) throw IllegalArgumentException("Invalid JWT token format")

        val payload = parts[1].decodeBase64String()
        val jsonObject = Json.parseToJsonElement(payload).jsonObject

        return UserInfo(
            id = jsonObject["sub"]?.jsonPrimitive?.content ?: "",
            name = jsonObject["name"]?.jsonPrimitive?.content,
            email = jsonObject["email"]?.jsonPrimitive?.content,
            photoUrl = jsonObject["picture"]?.jsonPrimitive?.content
        )
    }
}
