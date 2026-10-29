@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.quran.shared.pipeline.profile

import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.auth.repository.OidcAuthRepository
import com.quran.shared.auth.service.AuthService
import com.quran.shared.pipeline.SynchronizationEnvironment
import com.quran.shared.pipeline.sync.AuthServiceSyncAccount
import com.quran.shared.pipeline.sync.SyncClient
import com.quran.shared.pipeline.sync.SyncLifecycleGate
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.publicvalue.multiplatform.oidc.DefaultOpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectClientConfig
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * [ProfileRefresher] with the production auth stack (the real [AuthService], [OidcAuthRepository] and [AuthStorage]),
 * faking only the network: the profile endpoint and the OIDC token endpoint answer from a mock engine.
 */
class ProfileRefresherTest {
    private val main = StandardTestDispatcher()
    private val authConfig = AuthConfig(clientId = CLIENT_ID)
    private val storage = AuthStorage(
        org.publicvalue.multiplatform.oidc.tokenstore.SettingsTokenStore(InMemorySettingsStore()),
        MapSettings().toSuspendSettings(main),
        Json { ignoreUnknownKeys = true }
    )
    private val sessionUser = UserInfo(id = ACCOUNT, name = "Old Name", email = "user@example.com")

    /** The server's account, as the profile endpoint returns it. */
    private var serverProfile = profileJson(fullName = "New Name", firstName = "New", lastName = "Name")

    /** How the profile endpoint answers; it returns [serverProfile] unless a test changes it. */
    private var answerProfile: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respondJson(serverProfile)
    }
    private val profileRequests = mutableListOf<HttpRequestData>()
    private var tokenRefreshes = 0
    private var clock = 1_800_000_000_000L

    private lateinit var authService: AuthService
    private lateinit var refresher: ProfileRefresher

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @AfterTest
    fun tearDown() {
        if (::refresher.isInitialized) refresher.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a fetch publishes the profile's name and uploaded photo`() = runTest(main) {
        start()

        refresher.refresh()

        assertEquals(
            AuthState.Success(
                UserInfo(
                    id = ACCOUNT,
                    firstName = "New",
                    lastName = "Name",
                    name = "New Name",
                    email = "user@example.com",
                    photoUrl = "$AVATARS_HOST/events/banner/en/avatars/$ACCOUNT/medium.png?v=1788192266"
                )
            ),
            authService.authState.value
        )
        val request = profileRequests.single()
        assertEquals("https://apis.quran.foundation/auth/v1/users/profile", request.url.toString())
        assertEquals("access-token", request.headers["x-auth-token"])
        assertEquals(CLIENT_ID, request.headers["x-client-id"])
    }

    @Test
    fun `another account's profile is dropped and the session kept`() = runTest(main) {
        start()
        serverProfile = profileJson(id = "someone-else", fullName = "Someone Else")

        refresher.refresh()

        assertEquals(AuthState.Success(sessionUser), authService.authState.value)
        assertNull(storage.retrieveFetchedProfile())
    }

    @Test
    fun `a failure keeps the user and the session`() = runTest(main) {
        start()
        refresher.refresh()
        val user = authService.authState.value
        serverProfile = profileJson(fullName = "Newer Name")

        answerProfile = { respond("Something went wrong", HttpStatusCode.InternalServerError) }
        assertEquals(500, assertFailsWith<ProfileHttpException> { refresher.refresh() }.status)
        answerProfile = { respondJson("""{"success":true,"data":{"email":"user@example.com"}}""") }
        assertFailsWith<UnexpectedProfileResponseException> { refresher.refresh() }
        answerProfile = { throw IOException("offline") }
        assertFailsWith<IOException> { refresher.refresh() }

        assertEquals(user, authService.authState.value)
        assertTrue(authService.isLoggedIn())
        assertEquals("New Name", storage.retrieveFetchedProfile()?.name)
    }

    @Test
    fun `a logout during the fetch writes nothing`() = runTest(main) {
        start()
        val arrived = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit>()
        answerProfile = {
            arrived.complete(Unit)
            answer.await()
            respondJson(serverProfile)
        }

        val fetch = async { refresher.refresh() }
        arrived.await()
        authService.clearLocalSession()
        answer.complete(Unit)
        fetch.await()
        advanceUntilIdle()

        assertEquals(AuthState.Idle, authService.authState.value)
        assertNull(storage.retrieveFetchedProfile())
        assertNull(storage.retrieveProfileFetchAttemptedAt())
    }

    @Test
    fun `a 401 refreshes the token and tries once more`() = runTest(main) {
        start()
        answerProfile = { request ->
            if (request.headers["x-auth-token"] == "access-token") {
                respond("Token expired", HttpStatusCode.Unauthorized)
            } else {
                respondJson(serverProfile)
            }
        }

        refresher.refresh()

        assertEquals(1, tokenRefreshes)
        assertEquals(listOf("access-token", "refreshed-access-token"), profileRequests.map { it.headers["x-auth-token"] })
        assertEquals("New Name", (authService.authState.value as AuthState.Success).userInfo?.name)
    }

    @Test
    fun `a 401 after the refresh is not retried again`() = runTest(main) {
        start()
        answerProfile = { respond("Token expired", HttpStatusCode.Unauthorized) }

        assertEquals(401, assertFailsWith<ProfileHttpException> { refresher.refresh() }.status)

        assertEquals(1, tokenRefreshes)
        assertEquals(2, profileRequests.size)
        assertTrue(authService.isLoggedIn())
    }

    @Test
    fun `a call during a fetch joins it`() = runTest(main) {
        start()
        val answer = CompletableDeferred<Unit>()
        answerProfile = {
            answer.await()
            respondJson(serverProfile)
        }

        val first = async { refresher.refresh() }
        val second = async { refresher.refresh() }
        runCurrent()
        refresher.refreshIfDue()
        runCurrent()
        answer.complete(Unit)
        first.await()
        second.await()
        advanceUntilIdle()

        assertEquals(1, profileRequests.size)
    }

    /** The previous session's fetch would be dropped, so a refresh after a logout and sign-in starts its own. */
    @Test
    fun `a fetch for a previous session is not joined`() = runTest(main) {
        start()
        val firstArrived = CompletableDeferred<Unit>()
        val firstAnswer = CompletableDeferred<Unit>()
        answerProfile = {
            if (profileRequests.size == 1) {
                firstArrived.complete(Unit)
                firstAnswer.await()
                respondJson(profileJson(fullName = "Previous Session"))
            } else {
                respondJson(serverProfile)
            }
        }
        val first = async { refresher.refresh() }
        firstArrived.await()
        authService.clearLocalSession()
        signIn()
        assertTrue(authService.refreshAccessTokenIfNeeded(), "the new session is published")

        refresher.refresh()

        assertEquals(2, profileRequests.size)
        assertEquals("New Name", storage.retrieveFetchedProfile()?.name)
        firstAnswer.complete(Unit)
        first.await()
        advanceUntilIdle()
        assertEquals("New Name", storage.retrieveFetchedProfile()?.name, "the previous session's answer is dropped")
    }

    @Test
    fun `signed out nothing is fetched`() = runTest(main) {
        start(signedIn = false)

        refresher.refresh()
        refresher.refreshIfDue()
        advanceUntilIdle()

        assertEquals(0, profileRequests.size)
    }

    @Test
    fun `a sync fetches the session's first profile, then again only a day later`() = runTest(main) {
        start()

        syncRunAfter(Duration.ZERO)
        assertEquals(1, profileRequests.size, "a new session has no profile yet")
        syncRunAfter(23.hours)
        assertEquals(1, profileRequests.size, "the profile is less than a day old")
        syncRunAfter(1.hours)
        assertEquals(2, profileRequests.size, "the profile is a day old")
    }

    @Test
    fun `after a failed fetch a sync tries again only an hour later`() = runTest(main) {
        start()
        answerProfile = { throw IOException("offline") }

        syncRunAfter(Duration.ZERO)
        assertEquals(1, profileRequests.size)
        syncRunAfter(59.minutes)
        assertEquals(1, profileRequests.size, "the last attempt is less than an hour old")
        answerProfile = { respondJson(serverProfile) }
        syncRunAfter(1.minutes)
        assertEquals(2, profileRequests.size, "the last attempt is an hour old")
        assertEquals("New Name", storage.retrieveFetchedProfile()?.name)
    }

    @Test
    fun `an explicit refresh ignores the back-off`() = runTest(main) {
        start()
        syncRunAfter(Duration.ZERO)

        refresher.refresh()

        assertEquals(2, profileRequests.size)
    }

    /** Moves the clock on by [elapsed], then starts a sync run's due fetch and lets it finish. */
    private fun TestScope.syncRunAfter(elapsed: Duration) {
        clock += elapsed.inWholeMilliseconds
        refresher.refreshIfDue()
        advanceUntilIdle()
    }

    /** The production auth stack over the mock network, signed in as [sessionUser] unless [signedIn] is false. */
    private suspend fun TestScope.start(signedIn: Boolean = true) {
        if (signedIn) signIn()
        val engine = MockEngine(
            MockEngineConfig().apply {
                dispatcher = main
                addHandler { request ->
                    when (request.url.encodedPath) {
                        "/auth/v1/users/profile" -> {
                            profileRequests += request
                            answerProfile(request)
                        }
                        TOKEN_PATH -> {
                            tokenRefreshes += 1
                            respondJson(
                                """{"access_token":"refreshed-access-token","token_type":"Bearer","expires_in":3600}"""
                            )
                        }
                        else -> error("Unexpected request to ${request.url}")
                    }
                }
            }
        )
        val oidcClient = DefaultOpenIdConnectClient(
            httpClient = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } },
            config = OpenIdConnectClientConfig {
                endpoints {
                    authorizationEndpoint = authConfig.authorizationEndpoint
                    tokenEndpoint = authConfig.tokenEndpoint
                }
                clientId = authConfig.clientId
                redirectUri = authConfig.redirectUri
            }
        )
        authService = AuthService(OidcAuthRepository(authConfig, storage, oidcClient))
        advanceUntilIdle()
        val environment = SynchronizationEnvironment("https://apis.quran.foundation/auth", avatarsHost = AVATARS_HOST)
        refresher = ProfileRefresher(
            ProfileApi(SyncClient.createSyncHttpClient(engine), environment),
            AuthServiceSyncAccount(authService),
            object : SyncLifecycleGate {},
            main
        ) { clock }
    }

    private suspend fun signIn() {
        val generation = storage.retrieveSessionGeneration() + 1
        storage.storeNewSessionTokens(
            TokenResponse(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                idToken = idToken(),
                expiresIn = 3600,
                tokenType = "Bearer"
            ),
            sessionUser
        )
        storage.storeSessionGeneration(generation)
        storage.storeCommittedTokenGeneration(generation)
    }

    /** An unsigned ID token for [ACCOUNT] from this client's issuer, as the refresh check reads it. */
    private fun idToken(): String {
        val payload = buildJsonObject {
            put("sub", ACCOUNT)
            put("iss", authConfig.baseUrl)
            put("aud", buildJsonArray { add(JsonPrimitive(CLIENT_ID)) })
            put("name", "Old Name")
        }
        val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        return listOf("""{"alg":"RS256","typ":"JWT"}""", payload.toString(), "signature")
            .joinToString(".") { base64.encode(it.encodeToByteArray()) }
    }

    private fun profileJson(
        id: String = ACCOUNT,
        fullName: String,
        firstName: String = "",
        lastName: String = ""
    ): String =
        """{"success":true,"data":{"id":"$id","createdAt":"2022-12-06T06:16:20.229Z","email":"user@example.com",""" +
            """"firstName":"$firstName","lastName":"$lastName","username":"user","fullName":"$fullName",""" +
            """"photoUrl":".png.1788192266","isAdmin":false,"isPasswordSet":true}}"""

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private companion object {
        const val ACCOUNT = "6f1c2d3e-4a5b-4c6d-8e7f-90a1b2c3d4e5"
        const val CLIENT_ID = "client-id"
        const val TOKEN_PATH = "/oauth2/token"
        const val AVATARS_HOST = "https://avatars.example.invalid"
    }
}

private class InMemorySettingsStore : org.publicvalue.multiplatform.oidc.tokenstore.SettingsStore {
    private val values = mutableMapOf<String, String>()
    override suspend fun get(key: String): String? = values[key]
    override suspend fun put(key: String, value: String) { values[key] = value }
    override suspend fun remove(key: String) { values.remove(key) }
    override suspend fun clear() = values.clear()
}
