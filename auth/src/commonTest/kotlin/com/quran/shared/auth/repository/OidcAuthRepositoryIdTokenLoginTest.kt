package com.quran.shared.auth.repository

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import com.quran.shared.auth.TEST_CLIENT_ID
import com.quran.shared.auth.TEST_SUBJECT
import com.quran.shared.auth.di.AuthFlowFactoryProvider
import com.quran.shared.auth.di.AuthModule
import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.model.AuthenticationFailedException
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.auth.service.AuthService
import com.quran.shared.auth.testIdToken
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.publicvalue.multiplatform.oidc.DefaultOpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectClientConfig
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlow
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlowFactory
import org.publicvalue.multiplatform.oidc.flows.EndSessionFlow
import org.publicvalue.multiplatform.oidc.types.AuthCodeRequest
import org.publicvalue.multiplatform.oidc.types.CodeChallengeMethod
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sign-in reads the account from the token response's ID token. It used to wait on `GET /userinfo` and, when that
 * failed, finished without a known account, so every sync was skipped until the next refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OidcAuthRepositoryIdTokenLoginTest {
    private val authConfig = AuthConfig(clientId = TEST_CLIENT_ID)
    private val tokenStore = RecordingTokenStore()
    private val storage = AuthStorage(
        tokenStore = tokenStore,
        settings = MapSettings().toSuspendSettings(),
        json = Json { ignoreUnknownKeys = true }
    )
    private val requests = mutableListOf<String>()
    private val revocationBodies = mutableListOf<String>()
    private val logs = mutableListOf<Pair<Severity, String>>()
    private val logger = Logger(
        StaticConfig(
            minSeverity = Severity.Verbose,
            logWriterList = listOf(
                object : LogWriter() {
                    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                        logs += severity to listOfNotNull(message, throwable?.message).joinToString(": ")
                    }
                }
            )
        ),
        "OidcAuthRepository"
    )

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
    fun `login stores the account from the ID token with no request but the token exchange`() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = repositoryAnsweringTokenRequestsWith(
                idToken = testIdToken(
                    claims = mapOf("name" to "Ayşe Yılmaz", "first_name" to "Ayşe", "email" to "ayse@example.com")
                )
            )

            repository.login()

            assertEquals(listOf(authConfig.tokenEndpoint), requests)
            val expected = UserInfo(
                id = TEST_SUBJECT,
                firstName = "Ayşe",
                name = "Ayşe Yılmaz",
                email = "ayse@example.com"
            )
            assertEquals(expected, repository.getCurrentUser())
            assertEquals(expected, storage.retrieveUserInfo(), "the user is stored with the tokens")
            assertTrue(repository.isLoggedIn())
        }

    @Test
    fun `after login the auth service knows the account and publishes the user`() = runTest(main) {
        val repository = repositoryAnsweringTokenRequestsWith(
            idToken = testIdToken(claims = mapOf("name" to "Ayşe Yılmaz", "email" to "ayse@example.com"))
        )
        val service = AuthService(repository)
        advanceUntilIdle()

        service.login()

        assertEquals(TEST_SUBJECT, service.currentAccountId())
        val state = assertIs<AuthState.Success>(service.authState.value)
        assertEquals("Ayşe Yılmaz", state.userInfo?.name)
        assertEquals("ayse@example.com", state.userInfo?.email)
        assertEquals(listOf(authConfig.tokenEndpoint), requests)
        service.clearAndJoin()
    }

    @Test
    fun `login without an ID token fails and leaves no session`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryAnsweringTokenRequestsWith(idToken = null)

        val failure = assertFailsWith<IllegalStateException> { repository.login() }

        assertTrue("no ID token" in failure.message.orEmpty(), failure.message)
        assertNoSession(repository)
        assertEquals(listOf("token=refresh-token&client_id=client-id"), revocationBodies, "the unusable grant is revoked")
        assertTrue(logs.any { (severity, message) -> severity == Severity.Error && "no ID token" in message }, "$logs")
    }

    @Test
    fun `login with an ID token for another client or issuer fails and leaves no session`() =
        runTest(UnconfinedTestDispatcher()) {
            listOf(
                testIdToken(audience = listOf("other-client")),
                testIdToken(issuer = "https://accounts.example.com"),
                testIdToken(subject = ""),
                "opaque-id-token"
            ).forEach { idToken ->
                val repository = repositoryAnsweringTokenRequestsWith(idToken = idToken)

                assertFailsWith<IllegalStateException>(idToken) { repository.login() }

                assertNoSession(repository)
            }
        }

    @Test
    fun `the auth service reports an unusable ID token as a failed authentication`() = runTest(main) {
        val repository = repositoryAnsweringTokenRequestsWith(idToken = testIdToken(audience = listOf("other")))
        val service = AuthService(repository)
        advanceUntilIdle()

        val failure = assertFailsWith<AuthenticationFailedException> { service.login() }

        assertTrue("audience" in failure.message.orEmpty(), failure.message)
        assertNull(service.currentAccountId())
        assertFalse(service.isLoggedIn())
        assertNoSession(repository)
        service.clearAndJoin()
    }

    @Test
    fun `a reauthentication with an unusable ID token keeps the previous session and its account`() =
        runTest(UnconfinedTestDispatcher()) {
            repositoryAnsweringTokenRequestsWith(idToken = testIdToken(claims = mapOf("name" to "Old Name"))).login()
            val repository = repositoryAnsweringTokenRequestsWith(
                idToken = testIdToken(issuer = "https://evil.example")
            )

            assertFailsWith<IllegalStateException> { repository.loginWithReauthentication() }

            assertTrue(repository.isLoggedIn())
            assertEquals(UserInfo(id = TEST_SUBJECT, name = "Old Name"), repository.getCurrentUser())
        }

    @Test
    fun `login with an unusable ID token revokes the refresh token it received`() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = repositoryAnsweringTokenRequestsWith(idToken = testIdToken(audience = listOf("other")))

            assertFailsWith<IllegalStateException> { repository.login() }

            assertEquals(listOf(authConfig.tokenEndpoint, authConfig.revocationEndpoint), requests)
            assertEquals(listOf("token=refresh-token&client_id=client-id"), revocationBodies)
            assertNoSession(repository)
        }

    @Test
    fun `a failed revoke leaves the same sign-in failure`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryAnsweringTokenRequestsWith(
            idToken = testIdToken(audience = listOf("other")),
            revocationFails = true
        )

        val failure = assertFailsWith<IllegalStateException> { repository.login() }

        assertTrue(failure.message.orEmpty().startsWith("Sign-in failed: the ID token's audience"), failure.message)
        assertEquals(listOf(authConfig.tokenEndpoint, authConfig.revocationEndpoint), requests)
        assertNoSession(repository)
        assertTrue(logs.any { (severity, message) -> severity == Severity.Warn && "revoke" in message }, "$logs")
        assertTrue(logs.any { (severity, message) -> severity == Severity.Error && "audience" in message }, "$logs")
        assertTrue(logs.none { (_, message) -> "refresh-token" in message }, "token strings are never logged: $logs")
    }

    private suspend fun assertNoSession(repository: OidcAuthRepository) {
        assertFalse(repository.isLoggedIn())
        assertNull(repository.getCurrentUser())
        assertNull(tokenStore.getAccessToken())
        assertNull(storage.retrieveUserInfo())
        assertNull(storage.retrieveCommittedTokenGeneration())
    }

    /**
     * A repository on the production auth HTTP client and the real OIDC client, whose authorization server answers
     * every request: the token endpoint with [idToken] (or none), the revocation endpoint with 200 (or a network
     * failure when [revocationFails]), anything else with 404.
     */
    private fun repositoryAnsweringTokenRequestsWith(
        idToken: String?,
        revocationFails: Boolean = false
    ): OidcAuthRepository {
        val engine = MockEngine(
            MockEngineConfig().apply {
                addHandler { request ->
                    val url = request.url.toString()
                    requests += url
                    if (url == authConfig.revocationEndpoint) {
                        revocationBodies += request.body.toByteArray().decodeToString()
                        if (revocationFails) {
                            throw IOException("revocation endpoint unreachable")
                        }
                        return@addHandler respond("", HttpStatusCode.OK)
                    }
                    if (url != authConfig.tokenEndpoint) {
                        return@addHandler respond("", HttpStatusCode.NotFound)
                    }
                    val body = buildJsonObject {
                        put("access_token", "access-token")
                        put("token_type", "bearer")
                        put("expires_in", 3600)
                        put("refresh_token", "refresh-token")
                        idToken?.let { put("id_token", it) }
                    }
                    respond(
                        content = body.toString(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            }
        )
        val oidcClient = DefaultOpenIdConnectClient(
            httpClient = AuthModule.createAuthHttpClient(Json { ignoreUnknownKeys = true }, engine),
            config = OpenIdConnectClientConfig {
                endpoints {
                    authorizationEndpoint = authConfig.authorizationEndpoint
                    tokenEndpoint = authConfig.tokenEndpoint
                    revocationEndpoint = authConfig.revocationEndpoint
                }
                clientId = authConfig.clientId
                redirectUri = authConfig.redirectUri
                codeChallengeMethod = CodeChallengeMethod.S256
                disableNonce = true
            }
        )
        AuthFlowFactoryProvider.initialize(TokenExchangeAuthFlowFactory)
        return OidcAuthRepository(
            authConfig = authConfig,
            authStorage = storage,
            oidcClient = oidcClient,
            logger = logger
        )
    }

    /** The platform's browser step, skipped: it hands back an authorization code that the client exchanges. */
    private object TokenExchangeAuthFlowFactory : CodeAuthFlowFactory {
        override fun createAuthFlow(client: OpenIdConnectClient): CodeAuthFlow = object : CodeAuthFlow {
            override suspend fun getAccessToken(
                configureAuthUrl: (URLBuilder.() -> Unit)?,
                configureTokenExchange: (HttpRequestBuilder.() -> Unit)?
            ) = client.exchangeToken(client.createAuthorizationCodeRequest(), "auth-code", configureTokenExchange)

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
