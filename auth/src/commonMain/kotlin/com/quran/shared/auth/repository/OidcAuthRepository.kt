package com.quran.shared.auth.repository

import com.quran.shared.auth.di.AuthFlowFactoryProvider
import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.persistence.AuthStorage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.ContentTypeMatcher
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import io.ktor.util.encodeBase64
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.DefaultOpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectClientConfig
import org.publicvalue.multiplatform.oidc.types.CodeChallengeMethod
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse

/**
 * AuthRepository implementation that uses the OIDC library's CodeAuthFlow.
 */
class OidcAuthRepository(
    private val authConfig: AuthConfig,
    private val authStorage: AuthStorage,
    private val networkDataSource: AuthNetworkDataSource = AuthNetworkDataSource(authConfig)
) : AuthRepository {

    private val oidcClient = DefaultOpenIdConnectClient(
        httpClient = HttpClient {
            install(ContentNegotiation) {
                register(
                    contentTypeToSend = ContentType.Application.Json,
                    converter = KotlinxSerializationConverter(Json { ignoreUnknownKeys = true }),
                    contentTypeMatcher = object : ContentTypeMatcher {
                        override fun contains(contentType: ContentType): Boolean = true
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
            clientSecret = null
            scope = authConfig.scopes.joinToString(" ")
            redirectUri = authConfig.redirectUri
            codeChallengeMethod = CodeChallengeMethod.S256
            disableNonce = true
        }
    )

    private val configureTokenExchange: HttpRequestBuilder.() -> Unit = {
        authConfig.clientSecret?.let { secret ->
            val auth = "${authConfig.clientId}:$secret".encodeToByteArray().encodeBase64()
            header(HttpHeaders.Authorization, "Basic $auth")
        }
    }

    override suspend fun login() {
        val authFlow = AuthFlowFactoryProvider.factory.createAuthFlow(oidcClient)
        val response = authFlow.getAccessToken(configureTokenExchange = configureTokenExchange)
        handleTokenResponse(response)
    }

    override suspend fun refreshTokensIfNeeded(): Boolean {
        return try {
            val refreshToken = authStorage.retrieveStoredRefreshToken() ?: return false
            val expirationTime = authStorage.retrieveTokenExpiration()
            val currentTime = com.quran.shared.auth.utils.currentTimeMillis()

            if (currentTime >= expirationTime - 60_000) {
                val response = oidcClient.refreshToken(refreshToken, configure = configureTokenExchange)
                handleTokenResponse(response)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun logout() {
        try {
            val idToken = authStorage.retrieveStoredIdToken()
            if (AuthFlowFactoryProvider.isInitialized() && idToken != null) {
                AuthFlowFactoryProvider.factory.createEndSessionFlow(oidcClient).endSession(idToken)
            }
        } catch (e: Exception) {
            // Ignore end session failures
        } finally {
            authStorage.clearAllTokens()
        }
    }

    override fun getAccessToken(): String? = authStorage.retrieveStoredAccessToken()
    override fun isLoggedIn(): Boolean = getAccessToken() != null
    override fun getCurrentUser(): UserInfo? = authStorage.retrieveUserInfo() ?: tryParseUserFromToken()

    private suspend fun handleTokenResponse(response: AccessTokenResponse) {
        authStorage.storeTokens(TokenResponse.fromOidc(response))
        fetchAndStoreUserInfo()
    }

    private suspend fun fetchAndStoreUserInfo() {
        try {
            val token = getAccessToken() ?: return
            val userInfo = networkDataSource.fetchUserInfo(token)
            authStorage.storeUserInfo(userInfo)
        } catch (e: Exception) {
            // Log error
        }
    }

    private fun tryParseUserFromToken(): UserInfo? {
        val idToken = authStorage.retrieveStoredIdToken() ?: getAccessToken() ?: return null
        return UserInfo.fromJwt(idToken)
    }

    // Lifecycle methods for handling app restarts during auth
    suspend fun canContinueLogin(): Boolean = 
        AuthFlowFactoryProvider.isInitialized() && AuthFlowFactoryProvider.factory.createAuthFlow(oidcClient).canContinueLogin()

    suspend fun continueLogin() {
        if (canContinueLogin()) {
            val response = AuthFlowFactoryProvider.factory.createAuthFlow(oidcClient).continueLogin(configureTokenExchange)
            handleTokenResponse(response)
        }
    }
}
