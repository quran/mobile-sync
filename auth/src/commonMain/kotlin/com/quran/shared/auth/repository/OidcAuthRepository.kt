package com.quran.shared.auth.repository

import co.touchlab.kermit.Logger
import com.quran.shared.auth.di.AuthFlowFactoryProvider
import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.auth.utils.currentTimeMillis
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.util.encodeBase64
import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse

/**
 * AuthRepository implementation that uses the OIDC library's CodeAuthFlow.
 */
class OidcAuthRepository(
    private val authConfig: AuthConfig,
    private val authStorage: AuthStorage,
    private val oidcClient: OpenIdConnectClient,
    private val networkDataSource: AuthNetworkDataSource,
    private val logger: Logger = Logger.withTag("OidcAuthRepository")
) : AuthRepository {

    private var isExchangingToken = false

    // Safety margin to refresh tokens before they actually expire (5 minutes)
    private val REFRESH_SAFETY_MARGIN_MS = 5 * 60_000

    private val configureTokenExchange: HttpRequestBuilder.() -> Unit = {
        authConfig.clientSecret?.let { secret ->
            val auth = "${authConfig.clientId}:$secret".encodeToByteArray().encodeBase64()
            header(HttpHeaders.Authorization, "Basic $auth")
        }
    }

    override fun getAuthHeaders(): Map<String, String> {
        val token = getAccessToken()
        return if (token != null) {
            mapOf(
                "Authorization" to "Bearer $token",
                "x-client-id" to authConfig.clientId
            )
        } else {
            emptyMap()
        }
    }

    override suspend fun login() {
        if (isExchangingToken) return
        isExchangingToken = true
        try {
            val authFlow = AuthFlowFactoryProvider.factory.createAuthFlow(oidcClient)
            val response = authFlow.getAccessToken(configureTokenExchange = configureTokenExchange)
            handleTokenResponse(response)
        } catch (e: Exception) {
            logger.e(e) { "Login failed" }
            throw e
        } finally {
            isExchangingToken = false
        }
    }

    override suspend fun refreshTokensIfNeeded(): Boolean {
        val refreshToken = authStorage.retrieveStoredRefreshToken() ?: return false
        val expirationTime = authStorage.retrieveTokenExpiration()
        val currentTime = currentTimeMillis()

        // If current time is before the safety window, no refresh needed
        if (currentTime < expirationTime - REFRESH_SAFETY_MARGIN_MS) {
            return true
        }

        return try {
            val response = oidcClient.refreshToken(
                refreshToken = refreshToken,
                configure = configureTokenExchange
            )
            handleTokenResponse(response)
            true
        } catch (e: Exception) {
            logger.e(e) { "Token refresh failed, clearing session" }
            // Critical: If refresh fails, session is invalid. Clear everything.
            authStorage.clearAllTokens()
            false
        }
    }

    override suspend fun logout() {
        try {
            val idToken = authStorage.retrieveStoredIdToken()
            // Only attempt end-session if we have a token and the factory is ready
            if (AuthFlowFactoryProvider.isInitialized() && idToken != null) {
                AuthFlowFactoryProvider.factory.createEndSessionFlow(oidcClient).endSession(idToken)
            }
        } catch (e: Exception) {
            logger.w(e) { "End session failed during logout" }
            // Network failure during logout shouldn't stop us from clearing local data
        } finally {
            authStorage.clearAllTokens()
        }
    }

    override fun getAccessToken(): String? = authStorage.retrieveStoredAccessToken()
    override fun isLoggedIn(): Boolean = getAccessToken() != null
    override fun getCurrentUser(): UserInfo? =
        authStorage.retrieveUserInfo() ?: tryParseUserFromToken()

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
            logger.e(e) { "Failed to fetch user info from network" }
            // Non-critical: Login succeeded but profile fetch failed.
        }
    }

    private fun tryParseUserFromToken(): UserInfo? {
        val idToken = authStorage.retrieveStoredIdToken() ?: getAccessToken() ?: return null
        return UserInfo.fromJwt(idToken)
    }

    // Lifecycle methods for handling app restarts during auth
    suspend fun canContinueLogin(): Boolean =
        AuthFlowFactoryProvider.isInitialized() &&
                AuthFlowFactoryProvider.factory.createAuthFlow(oidcClient).canContinueLogin()

    suspend fun continueLogin() {
        // Prevent duplicate exchange at app resumes
        if (isExchangingToken || !canContinueLogin()) return

        isExchangingToken = true
        try {
            val authFlow = AuthFlowFactoryProvider.factory.createAuthFlow(oidcClient)
            // CRITICAL: Ensure the custom header config is passed here as well
            val response = authFlow.continueLogin(configureTokenExchange)
            handleTokenResponse(response)
        } finally {
            isExchangingToken = false
        }
    }

}