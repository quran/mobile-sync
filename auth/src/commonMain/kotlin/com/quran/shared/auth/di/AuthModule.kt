package com.quran.shared.auth.di

import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.model.AuthRuntimeConfig
import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.OidcAuthRepository
import com.quran.shared.auth.repository.UnconfiguredAuthRepository
import com.quran.shared.auth.utils.installAuthHttpLogging
import com.quran.shared.di.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
                    val httpClient = createAuthHttpClient(json)
                    val oidcClient = createOpenIdConnectClient(runtimeConfig.config, httpClient)
                    OidcAuthRepository(
                        authConfig = runtimeConfig.config,
                        authStorage = authStorage,
                        oidcClient = oidcClient
                    )
                }
                AuthRuntimeConfig.Unconfigured -> UnconfiguredAuthRepository(authStorage)
            }
        }

        /**
         * The client behind the OIDC library's token exchange, refresh and revocation calls. The explicit timeouts
         * keep a request that never answers (an app suspended mid-request, a stalled network) from hanging sign-in or
         * refresh. [engine] is for tests; null uses the platform's engine.
         */
        internal fun createAuthHttpClient(json: Json, engine: HttpClientEngine? = null): HttpClient {
            val configure: HttpClientConfig<*>.() -> Unit = {
                installAuthHttpLogging()
                install(ContentNegotiation) {
                    json(json)
                }
                install(HttpTimeout) {
                    connectTimeoutMillis = AUTH_CONNECT_TIMEOUT_MS
                    requestTimeoutMillis = AUTH_REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = AUTH_SOCKET_TIMEOUT_MS
                }
            }
            return if (engine == null) HttpClient(configure) else HttpClient(engine, configure)
        }

        internal const val AUTH_CONNECT_TIMEOUT_MS = 15_000L
        internal const val AUTH_REQUEST_TIMEOUT_MS = 30_000L
        internal const val AUTH_SOCKET_TIMEOUT_MS = 30_000L

        internal fun createOpenIdConnectClient(
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
