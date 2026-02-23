package com.quran.shared.auth.di

import com.quran.shared.auth.BuildKonfig
import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.OidcAuthRepository
import com.quran.shared.di.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import org.publicvalue.multiplatform.oidc.types.CodeChallengeMethod

typealias LibrarySettings = com.russhwolf.settings.Settings
typealias LibraryJson = kotlinx.serialization.json.Json
typealias LibraryHttpClient = io.ktor.client.HttpClient
typealias LibraryOpenIdConnectClient = org.publicvalue.multiplatform.oidc.OpenIdConnectClient

@ContributesTo(AppScope::class)
@BindingContainer
abstract class AuthModule {

    @Binds
    abstract fun bindAuthRepository(impl: OidcAuthRepository): AuthRepository

    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun provideSettings(): LibrarySettings {
            return com.russhwolf.settings.Settings()
        }

        @Provides
        @SingleIn(AppScope::class)
        fun provideJson(): LibraryJson {
            return kotlinx.serialization.json.Json {
                explicitNulls = false
                ignoreUnknownKeys = true
            }
        }

        @Provides
        @SingleIn(AppScope::class)
        fun provideAuthConfig(): AuthConfig {
            return AuthConfig(
                usePreProduction = true,
                clientId = BuildKonfig.CLIENT_ID,
                clientSecret = BuildKonfig.CLIENT_SECRET
            )
        }

        @Provides
        @SingleIn(AppScope::class)
        fun provideHttpClient(json: LibraryJson): LibraryHttpClient {
            return io.ktor.client.HttpClient {
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

        @Provides
        @SingleIn(AppScope::class)
        fun provideOpenIdConnectClient(
            config: AuthConfig,
            httpClient: LibraryHttpClient
        ): LibraryOpenIdConnectClient {
            return org.publicvalue.multiplatform.oidc.DefaultOpenIdConnectClient(
                httpClient = httpClient,
                config = org.publicvalue.multiplatform.oidc.OpenIdConnectClientConfig {
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
