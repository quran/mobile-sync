package com.quran.shared.auth.di

import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.model.AuthRuntimeConfig
import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.AuthNetworkDataSource
import com.quran.shared.auth.repository.OidcAuthRepository
import com.quran.shared.auth.repository.UnconfiguredAuthRepository
import com.quran.shared.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlin.native.HiddenFromObjC
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.DefaultOpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectClientConfig
import org.publicvalue.multiplatform.oidc.types.CodeChallengeMethod


@ContributesTo(AppScope::class)
@BindingContainer
@HiddenFromObjC
abstract class AuthModule {

    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun provideJson(): Json {
            return Json {
                explicitNulls = false
                ignoreUnknownKeys = true
            }
        }

        @Provides
        @SingleIn(AppScope::class)
        fun provideAuthRepository(
            runtimeConfig: AuthRuntimeConfig,
            authStorage: AuthStorage,
            json: Json
        ): AuthRepository {
            return when (runtimeConfig) {
                is AuthRuntimeConfig.Configured -> {
                    val httpClient = createHttpClient(json, runtimeConfig.config)
                    val oidcClient = createOpenIdConnectClient(runtimeConfig.config, httpClient)
                    OidcAuthRepository(
                        authConfig = runtimeConfig.config,
                        authStorage = authStorage,
                        oidcClient = oidcClient,
                        networkDataSource = AuthNetworkDataSource(
                            authConfig = runtimeConfig.config,
                            httpClient = httpClient
                        )
                    )
                }
                AuthRuntimeConfig.Unconfigured -> UnconfiguredAuthRepository(authStorage)
            }
        }

        private fun createHttpClient(json: Json, config: AuthConfig): HttpClient {
            return HttpClient {
                install(Logging) {
                    logger = object : Logger {
                        override fun log(message: String) = println("HTTP Client: $message")
                    }
                    level = if (config.environment.enableVerboseLogging) LogLevel.ALL else LogLevel.NONE
                }
                install(ContentNegotiation) {
                    json(json)
                }
            }
        }

        private fun createOpenIdConnectClient(
            config: AuthConfig,
            httpClient: HttpClient
        ): OpenIdConnectClient {
            return DefaultOpenIdConnectClient(
                httpClient = httpClient,
                config = OpenIdConnectClientConfig {
                    endpoints {
                        authorizationEndpoint = config.authorizationEndpoint
                        tokenEndpoint = config.tokenEndpoint
                        endSessionEndpoint = config.endSessionEndpoint
                        revocationEndpoint = config.revocationEndpoint
                    }
                    clientId = config.clientId
                    scope = config.scopes.joinToString(" ")
                    redirectUri = config.redirectUri
                    postLogoutRedirectUri = config.postLogoutRedirectUri
                    codeChallengeMethod = CodeChallengeMethod.S256
                    disableNonce = true
                }
            )
        }
    }
}
