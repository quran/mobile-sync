package com.quran.shared.auth.di

import com.quran.shared.auth.BuildKonfig
import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.AuthNetworkDataSource
import com.quran.shared.auth.repository.OidcAuthRepository
import com.quran.shared.auth.service.AuthService
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.DefaultOpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectClientConfig
import org.publicvalue.multiplatform.oidc.types.CodeChallengeMethod

object AuthConfigFactory {

    fun createDefaultAuthConfig(): AuthConfig {
        return AuthConfig(
            usePreProduction = true,
            clientId = BuildKonfig.CLIENT_ID,
            clientSecret = BuildKonfig.CLIENT_SECRET
        )
    }

    private val json = Json {
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    val sharedHttpClient: HttpClient by lazy {
        HttpClient {
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) = println("HTTP Client: $message")
                }
                level = LogLevel.ALL
            }
            install(ContentNegotiation) {
                json(json)
            }
        }
    }

    val oidcClient: OpenIdConnectClient by lazy {
        val config = createDefaultAuthConfig()
        DefaultOpenIdConnectClient(
            httpClient = sharedHttpClient,
            config = OpenIdConnectClientConfig {
                endpoints {
                    authorizationEndpoint = config.authorizationEndpoint
                    tokenEndpoint = config.tokenEndpoint
                    endSessionEndpoint = config.endSessionEndpoint
                    revocationEndpoint = config.revocationEndpoint
                }
                clientId = config.clientId
                clientSecret = null //config.clientSecret IMPORTANT!, passing the config value even if config.clientSecret is null makes api call fail
                scope = config.scopes.joinToString(" ")
                redirectUri = config.redirectUri
//                postLogoutRedirectUri = config.postLogoutRedirectUri
                codeChallengeMethod = CodeChallengeMethod.S256
                disableNonce = true
            }
        )
    }

    // Singletons
    val authStorage: AuthStorage by lazy { AuthStorage() }

    val authNetworkDataSource: AuthNetworkDataSource by lazy {
        AuthNetworkDataSource(
            authConfig = createDefaultAuthConfig(),
            httpClient = sharedHttpClient
        )
    }

    val authRepository: AuthRepository by lazy {
        OidcAuthRepository(
            authConfig = createDefaultAuthConfig(),
            authStorage = authStorage,
            oidcClient = oidcClient,
            networkDataSource = authNetworkDataSource
        )
    }

    val authService: AuthService by lazy {
        AuthService(authRepository)
    }
}