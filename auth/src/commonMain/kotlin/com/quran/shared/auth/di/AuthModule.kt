package com.quran.shared.auth.di

import com.quran.shared.auth.BuildKonfig
import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.OidcAuthRepository
import com.quran.shared.di.AppScope
import com.russhwolf.settings.Settings
import dev.zacsweers.metro.Binds
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
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.DefaultOpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectClientConfig
import org.publicvalue.multiplatform.oidc.types.CodeChallengeMethod


@ContributesTo(AppScope::class)
@BindingContainer
abstract class AuthModule {

    @Binds
    abstract fun bindAuthRepository(impl: OidcAuthRepository): AuthRepository

    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun provideSettings(): Settings {
            return Settings()
        }

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
        fun provideAuthConfig(): AuthConfig {
            return AuthConfig(
                usePreProduction = BuildKonfig.IS_DEBUG,
                clientId = BuildKonfig.CLIENT_ID,
                clientSecret = BuildKonfig.CLIENT_SECRET
            )
        }

        @Provides
        @SingleIn(AppScope::class)
        fun provideHttpClient(json: Json, config: AuthConfig): HttpClient {
            return HttpClient {
                install(Logging) {
                    logger = object : Logger {
                        override fun log(message: String) = println("HTTP Client: $message")
                    }
                    level = if (config.usePreProduction) LogLevel.NONE else LogLevel.ALL
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
