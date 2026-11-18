package com.quran.shared.auth.repository

import com.quran.shared.auth.TEST_CLIENT_ID
import com.quran.shared.auth.di.AuthFlowFactoryProvider
import com.quran.shared.auth.di.AuthModule
import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.auth.service.AuthService
import com.quran.shared.auth.testIdToken
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.DefaultOpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectClientConfig
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlow
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlowFactory
import org.publicvalue.multiplatform.oidc.flows.EndSessionFlow
import org.publicvalue.multiplatform.oidc.types.AuthCodeRequest
import org.publicvalue.multiplatform.oidc.types.CodeChallengeMethod
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The sign-in page's language: the caller's UI locales go to the authorization request as `ui_locales`. */
@OptIn(ExperimentalCoroutinesApi::class)
class OidcAuthRepositoryUiLocalesTest {
    private val authConfig = AuthConfig(clientId = TEST_CLIENT_ID)
    private val authorizationUrls = mutableListOf<Url>()

    /** [AuthService] runs its own work on Main. */
    private val main = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `login sends the non-blank UI locales as ui_locales separated by spaces`() =
        runTest(UnconfinedTestDispatcher()) {
            repository().login(uiLocales = listOf("ar", " ", "en-US "))

            assertEquals("ar en-US", authorizationUrls.single().parameters["ui_locales"])
        }

    @Test
    fun `login without UI locales sends no ui_locales`() = runTest(UnconfinedTestDispatcher()) {
        repository().login(uiLocales = emptyList())
        repository().login(uiLocales = listOf("", " "))

        assertEquals(listOf(null, null), authorizationUrls.map { it.parameters["ui_locales"] })
    }

    @Test
    fun `the auth service opens the sign-in page with the UI locales of the call that opens it`() = runTest(main) {
        val browserOpened = CompletableDeferred<Unit>()
        val browserCanFinish = CompletableDeferred<Unit>()
        val service = AuthService(
            repository(
                browser = {
                    browserOpened.complete(Unit)
                    browserCanFinish.await()
                }
            )
        )
        advanceUntilIdle()

        val firstTap = launch { service.login(uiLocales = listOf("ar")) }
        // Auth storage reads run on another dispatcher, so wait for the browser rather than the test scheduler.
        browserOpened.await()
        val secondTap = async { service.login(uiLocales = listOf("fr")) }
        runCurrent()
        assertFalse(secondTap.isCompleted, "the second call waits on the sign-in already running")
        browserCanFinish.complete(Unit)
        firstTap.join()
        secondTap.await()

        assertEquals("ar", authorizationUrls.single().parameters["ui_locales"])
        service.clearAndJoin()
    }

    /**
     * A repository on the real OIDC client, whose browser step records the authorization URL the client builds, runs
     * [browser], and answers with a session for the test account.
     */
    private fun repository(browser: suspend () -> Unit = {}): OidcAuthRepository {
        val oidcClient = DefaultOpenIdConnectClient(
            httpClient = AuthModule.createAuthHttpClient(
                Json { ignoreUnknownKeys = true },
                MockEngine { respond("", HttpStatusCode.NotFound) }
            ),
            config = OpenIdConnectClientConfig {
                endpoints {
                    authorizationEndpoint = authConfig.authorizationEndpoint
                    tokenEndpoint = authConfig.tokenEndpoint
                }
                clientId = authConfig.clientId
                redirectUri = authConfig.redirectUri
                codeChallengeMethod = CodeChallengeMethod.S256
            }
        )
        AuthFlowFactoryProvider.initialize(AuthorizationUrlRecordingFlowFactory(browser))
        return OidcAuthRepository(
            authConfig = authConfig,
            authStorage = AuthStorage(
                tokenStore = RecordingTokenStore(),
                settings = MapSettings().toSuspendSettings(),
                json = Json { ignoreUnknownKeys = true }
            ),
            oidcClient = oidcClient
        )
    }

    private inner class AuthorizationUrlRecordingFlowFactory(
        private val browser: suspend () -> Unit
    ) : CodeAuthFlowFactory {
        override fun createAuthFlow(client: OpenIdConnectClient): CodeAuthFlow = object : CodeAuthFlow {
            override suspend fun getAccessToken(
                configureAuthUrl: (URLBuilder.() -> Unit)?,
                configureTokenExchange: (HttpRequestBuilder.() -> Unit)?
            ): AccessTokenResponse {
                authorizationUrls += client.createAuthorizationCodeRequest(configureAuthUrl).url
                browser()
                return AccessTokenResponse(
                    access_token = "access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "refresh-token",
                    id_token = testIdToken()
                )
            }

            override suspend fun startLogin(configureAuthUrl: (URLBuilder.() -> Unit)?): AuthCodeRequest =
                throw UnsupportedOperationException("Not needed for this test")

            override suspend fun canContinueLogin(): Boolean = false

            override suspend fun continueLogin(configureTokenExchange: (HttpRequestBuilder.() -> Unit)?) =
                throw UnsupportedOperationException("Not needed for this test")
        }

        override fun createEndSessionFlow(client: OpenIdConnectClient): EndSessionFlow =
            throw UnsupportedOperationException("Not needed for this test")
    }
}
