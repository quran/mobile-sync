package com.quran.shared.auth.repository

import com.quran.shared.auth.di.AuthFlowFactoryProvider
import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.persistence.AuthStorage
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.SuspendSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectClientConfig
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlow
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlowFactory
import org.publicvalue.multiplatform.oidc.flows.EndSessionFlow
import org.publicvalue.multiplatform.oidc.tokenstore.TokenStore
import org.publicvalue.multiplatform.oidc.types.AuthCodeRequest
import org.publicvalue.multiplatform.oidc.types.EndSessionRequest
import org.publicvalue.multiplatform.oidc.types.TokenRequest
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse
import org.publicvalue.multiplatform.oidc.types.remote.OpenIdConnectConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OidcAuthRepositoryTest {
    @Test
    fun `in-flight refresh cannot restore tokens after local logout`() = runTest(UnconfinedTestDispatcher()) {
        val tokenStore = RecordingTokenStore(
            accessToken = "old-access-token",
            refreshToken = "refresh-token",
            idToken = "old-id-token"
        )
        val storage = AuthStorage(
            tokenStore = tokenStore,
            settings = MapSettings().toSuspendSettings(),
            json = Json { ignoreUnknownKeys = true }
        )
        storage.storeSessionGeneration(1)
        storage.storeCommittedTokenGeneration(1)
        val oidcClient = BlockingRefreshOidcClient(
            refreshResponse = AccessTokenResponse(
                access_token = "new-access-token",
                token_type = "Bearer",
                expires_in = 3600,
                refresh_token = "new-refresh-token",
                id_token = "new-id-token"
            )
        )
        val repository = OidcAuthRepository(
            authConfig = AuthConfig(clientId = "client-id"),
            authStorage = storage,
            oidcClient = oidcClient,
            networkDataSource = AuthNetworkDataSource(
                authConfig = AuthConfig(clientId = "client-id"),
                httpClient = HttpClient(MockEngine) {
                    engine {
                        addHandler {
                            respond(
                                content = "{}",
                                status = HttpStatusCode.OK,
                                headers = headersOf("Content-Type", ContentType.Application.Json.toString())
                            )
                        }
                    }
                }
            )
        )

        val refresh = async(start = CoroutineStart.UNDISPATCHED) {
            repository.refreshTokensIfNeeded()
        }
        oidcClient.refreshStarted.await()

        repository.clearLocalSession()
        assertNull(storage.retrieveStoredAccessToken())

        oidcClient.refreshCanFinish.complete(Unit)

        assertFalse(refresh.await())
        assertNull(storage.retrieveStoredAccessToken())
        assertNull(storage.retrieveStoredRefreshToken())
        assertNull(storage.retrieveStoredIdToken())
    }

    @Test
    fun `persisted logout invalidation hides surviving tokens after token clear failure and restart`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token",
                failRemoveAccessToken = true
            )
            val storage = AuthStorage(
                tokenStore = tokenStore,
                settings = settings,
                json = Json { ignoreUnknownKeys = true }
            )
            storage.storeSessionGeneration(1)
            storage.storeCommittedTokenGeneration(1)
            val repository = oidcRepository(storage)

            assertFailsWith<IllegalStateException> {
                repository.clearLocalSession()
            }
            assertEquals("old-access-token", tokenStore.getAccessToken())

            val restartedRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )

            assertNull(restartedRepository.getAccessToken())
            assertFalse(restartedRepository.isLoggedIn())
            assertEquals(emptyMap(), restartedRepository.getAuthHeaders())

            val authFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "fresh-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "fresh-refresh-token",
                    id_token = "fresh-id-token"
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(authFlow))

            authFlow.loginCanFinish.complete(Unit)
            restartedRepository.login()

            assertEquals("fresh-access-token", restartedRepository.getAccessToken())
            assertEquals("fresh-refresh-token", tokenStore.getRefreshToken())
            assertEquals("fresh-id-token", tokenStore.getIdToken())
        }

    @Test
    fun `failed explicit login after logout invalidation does not publish surviving old tokens after restart`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token",
                failRemoveAccessToken = true
            )
            val storage = AuthStorage(
                tokenStore = tokenStore,
                settings = settings,
                json = Json { ignoreUnknownKeys = true }
            )
            storage.storeSessionGeneration(1)
            storage.storeCommittedTokenGeneration(1)
            val repository = oidcRepository(storage)

            assertFailsWith<IllegalStateException> {
                repository.clearLocalSession()
            }
            AuthFlowFactoryProvider.initialize(
                StaticCodeAuthFlowFactory(
                    FailingLoginAuthFlow(IllegalStateException("login failed before tokens"))
                )
            )

            val postLogoutRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )
            assertFailsWith<IllegalStateException> {
                postLogoutRepository.login()
            }

            val restartedRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )
            assertNull(restartedRepository.getAccessToken())
            assertEquals(emptyMap(), restartedRepository.getAuthHeaders())

            val successfulAuthFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "fresh-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "fresh-refresh-token",
                    id_token = "fresh-id-token"
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(successfulAuthFlow))

            successfulAuthFlow.loginCanFinish.complete(Unit)
            restartedRepository.login()

            assertEquals("fresh-access-token", restartedRepository.getAccessToken())
            assertEquals("fresh-refresh-token", tokenStore.getRefreshToken())
            assertEquals("fresh-id-token", tokenStore.getIdToken())
        }

    @Test
    fun `stale refresh failure does not invalidate explicit login in flight`() =
        runTest(UnconfinedTestDispatcher()) {
            val tokenStore = RecordingTokenStore(
                accessToken = "old-access-token",
                refreshToken = "refresh-token",
                idToken = "old-id-token"
            )
            val storage = AuthStorage(
                tokenStore = tokenStore,
                settings = MapSettings().toSuspendSettings(),
                json = Json { ignoreUnknownKeys = true }
            )
            storage.storeSessionGeneration(1)
            storage.storeCommittedTokenGeneration(1)
            val authFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "fresh-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "fresh-refresh-token",
                    id_token = "fresh-id-token"
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(authFlow))
            val oidcClient = BlockingRefreshOidcClient(
                refreshResponse = AccessTokenResponse(
                    access_token = "unused-refresh-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                refreshFailure = IllegalStateException("refresh failed")
            )
            val repository = OidcAuthRepository(
                authConfig = AuthConfig(clientId = "client-id"),
                authStorage = storage,
                oidcClient = oidcClient,
                networkDataSource = AuthNetworkDataSource(
                    authConfig = AuthConfig(clientId = "client-id"),
                    httpClient = HttpClient(MockEngine) {
                        engine {
                            addHandler {
                                respond(
                                    content = "{}",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", ContentType.Application.Json.toString())
                                )
                            }
                        }
                    }
                )
            )

            val refresh = async(start = CoroutineStart.UNDISPATCHED) {
                repository.refreshTokensIfNeeded()
            }
            withContext(Dispatchers.Default) {
                withTimeout(1_000) {
                    oidcClient.refreshStarted.await()
                }
            }

            val explicitLogin = async(start = CoroutineStart.UNDISPATCHED) {
                repository.login()
            }
            withContext(Dispatchers.Default) {
                withTimeout(1_000) {
                    authFlow.loginStarted.await()
                }
            }

            oidcClient.refreshCanFinish.complete(Unit)
            assertFalse(refresh.await())

            authFlow.loginCanFinish.complete(Unit)
            explicitLogin.await()

            assertEquals("fresh-access-token", storage.retrieveStoredAccessToken())
            assertEquals("fresh-refresh-token", storage.retrieveStoredRefreshToken())
            assertEquals("fresh-id-token", storage.retrieveStoredIdToken())
        }

    @Test
    fun `logout invalidates explicit login that started before logout`() =
        runTest(UnconfinedTestDispatcher()) {
            val storage = AuthStorage(
                tokenStore = RecordingTokenStore(),
                settings = MapSettings().toSuspendSettings(),
                json = Json { ignoreUnknownKeys = true }
            )
            val authFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "stale-login-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "stale-login-refresh-token",
                    id_token = "stale-login-id-token"
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(authFlow))
            val repository = OidcAuthRepository(
                authConfig = AuthConfig(clientId = "client-id"),
                authStorage = storage,
                oidcClient = BlockingRefreshOidcClient(
                    refreshResponse = AccessTokenResponse(
                        access_token = "unused-refresh-access-token",
                        token_type = "Bearer",
                        expires_in = 3600
                    )
                ),
                networkDataSource = AuthNetworkDataSource(
                    authConfig = AuthConfig(clientId = "client-id"),
                    httpClient = HttpClient(MockEngine) {
                        engine {
                            addHandler {
                                respond(
                                    content = "{}",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", ContentType.Application.Json.toString())
                                )
                            }
                        }
                    }
                )
            )

            val explicitLogin = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { repository.login() }
            }
            withContext(Dispatchers.Default) {
                withTimeout(1_000) {
                    authFlow.loginStarted.await()
                }
            }

            repository.clearLocalSession()

            authFlow.loginCanFinish.complete(Unit)
            assertTrue(explicitLogin.await().isFailure)

            assertNull(storage.retrieveStoredAccessToken())
            assertNull(storage.retrieveStoredRefreshToken())
            assertNull(storage.retrieveStoredIdToken())
    }

    @Test
    fun `remote logout revoke cancellation propagates`() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = oidcRepository(
                AuthStorage(
                    tokenStore = RecordingTokenStore(),
                    settings = MapSettings().toSuspendSettings(),
                    json = Json { ignoreUnknownKeys = true }
                ),
                oidcClient = BlockingRefreshOidcClient(
                    refreshResponse = AccessTokenResponse(
                        access_token = "unused-access-token",
                        token_type = "Bearer",
                        expires_in = 3600
                    ),
                    revokeFailure = CancellationException("revoke cancelled")
                )
            )

            assertFailsWith<CancellationException> {
                repository.attemptRemoteLogout(LogoutTokenMaterial(refreshToken = "refresh-token", idToken = null))
            }
        }

    @Test
    fun `remote logout end session cancellation propagates`() =
        runTest(UnconfinedTestDispatcher()) {
            AuthFlowFactoryProvider.initialize(CancellingEndSessionFlowFactory)
            val repository = oidcRepository(
                AuthStorage(
                    tokenStore = RecordingTokenStore(),
                    settings = MapSettings().toSuspendSettings(),
                    json = Json { ignoreUnknownKeys = true }
                )
            )

            assertFailsWith<CancellationException> {
                repository.attemptRemoteLogout(LogoutTokenMaterial(refreshToken = null, idToken = "id-token"))
            }
        }

    @Test
    fun `late login response cannot commit after failed local clear removed active exchange`() =
        runTest(UnconfinedTestDispatcher()) {
            val tokenStore = RecordingTokenStore(failRemoveAccessToken = true)
            val storage = AuthStorage(
                tokenStore = tokenStore,
                settings = MapSettings().toSuspendSettings(),
                json = Json { ignoreUnknownKeys = true }
            )
            val authFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "stale-login-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "stale-login-refresh-token",
                    id_token = "stale-login-id-token"
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(authFlow))
            val repository = oidcRepository(storage)

            val login = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { repository.login() }
            }
            authFlow.loginStarted.await()

            assertFailsWith<IllegalStateException> {
                repository.clearLocalSession()
            }
            assertNull(repository.getAccessToken())

            authFlow.loginCanFinish.complete(Unit)

            assertTrue(login.await().isFailure)
            assertNull(tokenStore.getAccessToken())
            assertNull(tokenStore.getRefreshToken())
            assertNull(tokenStore.getIdToken())
        }

    @Test
    fun `fresh explicit login ignores abandoned pending marker and commits new tokens`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MapSettings().toSuspendSettings()
            settings.putLong("pending_login_start_generation", 1)
            val storage = AuthStorage(
                tokenStore = RecordingTokenStore(),
                settings = settings,
                json = Json { ignoreUnknownKeys = true }
            )
            storage.storeSessionGeneration(1)
            val authFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "stale-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "stale-pending-refresh-token",
                    id_token = "stale-pending-id-token"
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "fresh-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "fresh-refresh-token",
                    id_token = "fresh-id-token"
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(authFlow))
            val repository = OidcAuthRepository(
                authConfig = AuthConfig(clientId = "client-id"),
                authStorage = storage,
                oidcClient = BlockingRefreshOidcClient(
                    refreshResponse = AccessTokenResponse(
                        access_token = "unused-access-token",
                        token_type = "Bearer",
                        expires_in = 3600
                    )
                ),
                networkDataSource = AuthNetworkDataSource(
                    authConfig = AuthConfig(clientId = "client-id"),
                    httpClient = HttpClient(MockEngine) {
                        engine {
                            addHandler {
                                respond(
                                    content = "{}",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", ContentType.Application.Json.toString())
                                )
                            }
                        }
                    }
                )
            )

            authFlow.loginCanFinish.complete(Unit)
            repository.login()

            assertEquals(1, authFlow.getAccessTokenCalls)
            assertEquals(0, authFlow.continueLoginCalls)
            assertEquals("fresh-access-token", storage.retrieveStoredAccessToken())
            assertEquals("fresh-refresh-token", storage.retrieveStoredRefreshToken())
            assertEquals("fresh-id-token", storage.retrieveStoredIdToken())
        }

    @Test
    fun `fresh explicit login after logout and restart stores newer session tokens`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore()
            val authFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "stale-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "fresh-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "fresh-refresh-token",
                    id_token = "fresh-id-token"
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(authFlow))
            val repository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )

            repository.clearLocalSession()

            val restartedRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )
            authFlow.loginCanFinish.complete(Unit)
            restartedRepository.login()

            assertEquals(1, authFlow.getAccessTokenCalls)
            assertEquals("fresh-access-token", tokenStore.getAccessToken())
            assertEquals("fresh-refresh-token", tokenStore.getRefreshToken())
            assertEquals("fresh-id-token", tokenStore.getIdToken())
        }

    @Test
    fun `legacy tokens without committed generation are hidden when no pending login generation is reserved`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore(
                accessToken = "legacy-access-token",
                refreshToken = "legacy-refresh-token",
                idToken = "legacy-id-token"
            )
            val storage = AuthStorage(
                tokenStore = tokenStore,
                settings = settings,
                json = Json { ignoreUnknownKeys = true }
            )
            storage.storeSessionGeneration(1)

            val restartedRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )

            assertNull(restartedRepository.getAccessToken())
            assertFalse(restartedRepository.isLoggedIn())
            assertEquals(emptyMap(), restartedRepository.getAuthHeaders())
        }

    @Test
    fun `stored tokens are hidden when generation metadata was never committed`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore(
                accessToken = "uncommitted-access-token",
                refreshToken = "uncommitted-refresh-token",
                idToken = "uncommitted-id-token"
            )

            val restartedRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )

            assertNull(restartedRepository.getAccessToken())
            assertFalse(restartedRepository.isLoggedIn())
            assertEquals(emptyMap(), restartedRepository.getAuthHeaders())
        }

    @Test
    fun `failed token commit keeps partially written tokens hidden after restart`() =
        runTest(UnconfinedTestDispatcher()) {
            val baseSettings = MapSettings().toSuspendSettings()
            val failingSettings = FailingSettings(
                delegate = baseSettings,
                failKey = "committed_token_generation"
            )
            val tokenStore = RecordingTokenStore()
            val storage = AuthStorage(
                tokenStore = tokenStore,
                settings = failingSettings,
                json = Json { ignoreUnknownKeys = true }
            )
            val authFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "partial-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "partial-refresh-token",
                    id_token = "partial-id-token"
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(authFlow))
            val repository = oidcRepository(storage)

            authFlow.loginCanFinish.complete(Unit)

            assertFailsWith<IllegalStateException> {
                repository.login()
            }
            assertEquals("partial-access-token", tokenStore.getAccessToken())

            val restartedRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = baseSettings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )

            assertNull(restartedRepository.getAccessToken())
            assertFalse(restartedRepository.isLoggedIn())
            assertEquals(emptyMap(), restartedRepository.getAuthHeaders())
        }

    @Test
    fun `failed replacement login keeps previous committed session publishable`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token"
            )
            val storage = AuthStorage(
                tokenStore = tokenStore,
                settings = settings,
                json = Json { ignoreUnknownKeys = true }
            )
            storage.storeSessionGeneration(1)
            storage.storeCommittedTokenGeneration(1)
            AuthFlowFactoryProvider.initialize(
                StaticCodeAuthFlowFactory(
                    FailingLoginAuthFlow(IllegalStateException("replacement login failed before tokens"))
                )
            )
            val repository = oidcRepository(storage)

            assertFailsWith<IllegalStateException> {
                repository.loginWithReauthentication()
            }

            assertEquals("old-access-token", repository.getAccessToken())
            assertTrue(repository.isLoggedIn())

            val restartedRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )

            assertEquals("old-access-token", restartedRepository.getAccessToken())
            assertTrue(restartedRepository.isLoggedIn())
        }

    @Test
    fun `failed refresh commit keeps newly written tokens hidden after restart`() =
        runTest(UnconfinedTestDispatcher()) {
            val baseSettings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token"
            )
            val committedStorage = AuthStorage(
                tokenStore = tokenStore,
                settings = baseSettings,
                json = Json { ignoreUnknownKeys = true }
            )
            committedStorage.storeSessionGeneration(1)
            committedStorage.storeCommittedTokenGeneration(1)
            val refreshClient = BlockingRefreshOidcClient(
                refreshResponse = AccessTokenResponse(
                    access_token = "partial-refresh-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "partial-refresh-token",
                    id_token = "partial-refresh-id-token"
                )
            )
            val repository = oidcRepository(
                storage = AuthStorage(
                    tokenStore = tokenStore,
                    settings = FailingSettings(
                        delegate = baseSettings,
                        failKey = "committed_token_generation"
                    ),
                    json = Json { ignoreUnknownKeys = true }
                ),
                oidcClient = refreshClient
            )

            val refresh = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { repository.refreshTokensIfNeeded() }
            }
            refreshClient.refreshStarted.await()
            refreshClient.refreshCanFinish.complete(Unit)

            assertTrue(refresh.await().isFailure)
            assertEquals("partial-refresh-access-token", tokenStore.getAccessToken())

            val restartedRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = baseSettings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )

            assertNull(restartedRepository.getAccessToken())
            assertFalse(restartedRepository.isLoggedIn())
            assertEquals(emptyMap(), restartedRepository.getAuthHeaders())
        }

    @Test
    fun `refresh cancellation does not clear the committed local session`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token"
            )
            val storage = AuthStorage(
                tokenStore = tokenStore,
                settings = settings,
                json = Json { ignoreUnknownKeys = true }
            )
            storage.storeSessionGeneration(1)
            storage.storeCommittedTokenGeneration(1)
            val oidcClient = BlockingRefreshOidcClient(
                refreshResponse = AccessTokenResponse(
                    access_token = "unused-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                )
            )
            val repository = oidcRepository(storage, oidcClient)

            val refresh = async(start = CoroutineStart.UNDISPATCHED) {
                repository.refreshTokensIfNeeded()
            }
            oidcClient.refreshStarted.await()

            oidcClient.refreshCanFinish.completeExceptionally(CancellationException("refresh cancelled"))

            assertFailsWith<CancellationException> {
                refresh.await()
            }
            assertEquals("old-access-token", oidcRepository(storage).getAccessToken())
            assertEquals("old-access-token", tokenStore.getAccessToken())
            assertEquals("old-refresh-token", tokenStore.getRefreshToken())
            assertEquals("old-id-token", tokenStore.getIdToken())
        }

    @Test
    fun `refresh failure propagates local invalidation failure`() =
        runTest(UnconfinedTestDispatcher()) {
            val baseSettings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token",
                failRemoveAccessToken = true
            )
            val committedStorage = AuthStorage(
                tokenStore = tokenStore,
                settings = baseSettings,
                json = Json { ignoreUnknownKeys = true }
            )
            committedStorage.storeSessionGeneration(1)
            committedStorage.storeCommittedTokenGeneration(1)
            val refreshClient = BlockingRefreshOidcClient(
                refreshResponse = AccessTokenResponse(
                    access_token = "unused-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                refreshFailure = IllegalStateException("refresh failed")
            )
            val repository = oidcRepository(
                storage = AuthStorage(
                    tokenStore = tokenStore,
                    settings = FailingSettings(
                        delegate = baseSettings,
                        failKeys = setOf(
                            "session_generation"
                        ),
                        failRemoveKeys = setOf(
                            "committed_token_generation",
                            "committed_token_write_generation"
                        )
                    ),
                    json = Json { ignoreUnknownKeys = true }
                ),
                oidcClient = refreshClient
            )

            val refresh = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { repository.refreshTokensIfNeeded() }
            }
            refreshClient.refreshStarted.await()
            refreshClient.refreshCanFinish.complete(Unit)

            val result = refresh.await()
            assertTrue(result.isFailure)
            assertEquals("failed to remove committed_token_generation", result.exceptionOrNull()?.message)
            assertEquals("old-access-token", tokenStore.getAccessToken())
        }

    @Test
    fun `missing refresh token propagates local invalidation failure`() =
        runTest(UnconfinedTestDispatcher()) {
            val baseSettings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore(
                accessToken = "old-access-token",
                refreshToken = null,
                idToken = "old-id-token",
                failRemoveAccessToken = true
            )
            val committedStorage = AuthStorage(
                tokenStore = tokenStore,
                settings = baseSettings,
                json = Json { ignoreUnknownKeys = true }
            )
            committedStorage.storeTokens(
                TokenResponse(
                    accessToken = "old-access-token",
                    refreshToken = null,
                    idToken = "old-id-token",
                    expiresIn = 0,
                    tokenType = "Bearer"
                )
            )
            committedStorage.storeSessionGeneration(1)
            committedStorage.storeCommittedTokenGeneration(1)
            val repository = oidcRepository(
                storage = AuthStorage(
                    tokenStore = tokenStore,
                    settings = FailingSettings(
                        delegate = baseSettings,
                        failKeys = setOf(
                            "session_generation"
                        ),
                        failRemoveKeys = setOf(
                            "committed_token_generation",
                            "committed_token_write_generation"
                        )
                    ),
                    json = Json { ignoreUnknownKeys = true }
                )
            )

            val failure = assertFailsWith<IllegalStateException> {
                repository.refreshTokensIfNeeded()
            }

            assertEquals("failed to remove committed_token_generation", failure.message)
            assertEquals("old-access-token", tokenStore.getAccessToken())
        }

    @Test
    fun `partial marker clear failure hides stale local session after restart`() =
        runTest(UnconfinedTestDispatcher()) {
            val baseSettings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token",
                failRemoveAccessToken = true
            )
            val committedStorage = AuthStorage(
                tokenStore = tokenStore,
                settings = baseSettings,
                json = Json { ignoreUnknownKeys = true }
            )
            committedStorage.storeSessionGeneration(1)
            committedStorage.storeCommittedTokenGeneration(1)
            val clearingRepository = oidcRepository(
                storage = AuthStorage(
                    tokenStore = tokenStore,
                    settings = FailingSettings(
                        delegate = baseSettings,
                        failKeys = setOf("session_generation"),
                        failRemoveKeys = setOf("committed_token_generation")
                    ),
                    json = Json { ignoreUnknownKeys = true }
                )
            )

            val failure = assertFailsWith<IllegalStateException> {
                clearingRepository.clearLocalSession()
            }

            assertEquals("failed to remove committed_token_generation", failure.message)
            assertEquals("old-access-token", tokenStore.getAccessToken())
            val restartedRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = baseSettings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )
            assertNull(restartedRepository.getAccessToken())
            assertFalse(restartedRepository.isLoggedIn())
            assertEquals(emptyMap(), restartedRepository.getAuthHeaders())
        }

    @Test
    fun `token write cancellation keeps previously hidden stale local session hidden after restart`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token",
                cancelBeforeSaveTokens = true
            )
            val storage = AuthStorage(
                tokenStore = tokenStore,
                settings = settings,
                json = Json { ignoreUnknownKeys = true }
            )
            settings.putLong("token_write_generation", 1)
            storage.storeSessionGeneration(1)
            storage.storeCommittedTokenGeneration(1)
            settings.remove("token_write_generation")
            assertNull(oidcRepository(storage).getAccessToken())

            assertFailsWith<CancellationException> {
                storage.storeTokens(
                    TokenResponse(
                        accessToken = "new-access-token",
                        refreshToken = "new-refresh-token",
                        idToken = "new-id-token",
                        expiresIn = 3600,
                        tokenType = "Bearer"
                    )
                )
            }

            assertEquals("old-access-token", tokenStore.getAccessToken())
            val restartedRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )
            assertNull(restartedRepository.getAccessToken())
            assertFalse(restartedRepository.isLoggedIn())
            assertEquals(emptyMap(), restartedRepository.getAuthHeaders())
        }

    @Test
    fun `refresh cancellation before token save preserves committed local session after restart`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token",
                cancelBeforeSaveTokens = true
            )
            val storage = AuthStorage(
                tokenStore = tokenStore,
                settings = settings,
                json = Json { ignoreUnknownKeys = true }
            )
            storage.storeSessionGeneration(1)
            storage.storeCommittedTokenGeneration(1)
            val refreshClient = BlockingRefreshOidcClient(
                refreshResponse = AccessTokenResponse(
                    access_token = "new-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "new-refresh-token",
                    id_token = "new-id-token"
                )
            )
            val repository = oidcRepository(storage, refreshClient)

            val refresh = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { repository.refreshTokensIfNeeded() }
            }
            refreshClient.refreshStarted.await()
            refreshClient.refreshCanFinish.complete(Unit)

            assertTrue(refresh.await().isFailure)
            assertEquals("old-access-token", tokenStore.getAccessToken())

            val restartedRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )

            assertEquals("old-access-token", restartedRepository.getAccessToken())
            assertTrue(restartedRepository.isLoggedIn())
        }

    @Test
    fun `refresh cancellation after partial token save restores committed local session after restart`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token",
                cancelAfterPartialSaveTokens = true
            )
            val storage = AuthStorage(
                tokenStore = tokenStore,
                settings = settings,
                json = Json { ignoreUnknownKeys = true }
            )
            storage.storeSessionGeneration(1)
            storage.storeCommittedTokenGeneration(1)
            val refreshClient = BlockingRefreshOidcClient(
                refreshResponse = AccessTokenResponse(
                    access_token = "partial-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "partial-refresh-token",
                    id_token = "partial-id-token"
                )
            )
            val repository = oidcRepository(storage, refreshClient)

            val refresh = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { repository.refreshTokensIfNeeded() }
            }
            refreshClient.refreshStarted.await()
            refreshClient.refreshCanFinish.complete(Unit)

            assertTrue(refresh.await().isFailure)
            assertEquals("old-access-token", tokenStore.getAccessToken())
            assertEquals("old-refresh-token", tokenStore.getRefreshToken())

            val restartedRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )

            assertEquals("old-access-token", restartedRepository.getAccessToken())
            assertTrue(restartedRepository.isLoggedIn())
        }

    @Test
    fun `same intent repository login waits for active exchange result`() =
        runTest(UnconfinedTestDispatcher()) {
            val storage = AuthStorage(
                tokenStore = RecordingTokenStore(),
                settings = MapSettings().toSuspendSettings(),
                json = Json { ignoreUnknownKeys = true }
            )
            val authFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "login-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "login-refresh-token",
                    id_token = "login-id-token"
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(authFlow))
            val repository = oidcRepository(storage)

            val firstLogin = async(start = CoroutineStart.UNDISPATCHED) {
                val failure: Exception? = try {
                    repository.login()
                    null
                } catch (e: Exception) {
                    e
                }
                failure
            }
            authFlow.loginStarted.await()

            val duplicateLogin = async(start = CoroutineStart.UNDISPATCHED) {
                val failure: Exception? = try {
                    repository.login()
                    null
                } catch (e: Exception) {
                    e
                }
                failure
            }

            assertFalse(duplicateLogin.isCompleted)

            authFlow.loginCanFinish.complete(Unit)
            firstLogin.await()
            duplicateLogin.await()

            assertEquals(1, authFlow.getAccessTokenCalls)
            assertEquals("login-access-token", storage.retrieveStoredAccessToken())
        }

    @Test
    fun `same intent repository login shares active exchange failure`() =
        runTest(UnconfinedTestDispatcher()) {
            val storage = AuthStorage(
                tokenStore = RecordingTokenStore(),
                settings = FailingSettings(
                    delegate = MapSettings().toSuspendSettings(),
                    failKey = "committed_token_generation"
                ),
                json = Json { ignoreUnknownKeys = true }
            )
            val authFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "partial-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "partial-refresh-token",
                    id_token = "partial-id-token"
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(authFlow))
            val repository = oidcRepository(storage)

            val firstLogin = async(start = CoroutineStart.UNDISPATCHED) {
                val failure: Exception? = try {
                    repository.login()
                    null
                } catch (e: Exception) {
                    e
                }
                failure
            }
            authFlow.loginStarted.await()

            val duplicateLogin = async(start = CoroutineStart.UNDISPATCHED) {
                val failure: Exception? = try {
                    repository.login()
                    null
                } catch (e: Exception) {
                    e
                }
                failure
            }

            assertFalse(duplicateLogin.isCompleted)

            authFlow.loginCanFinish.complete(Unit)

            assertEquals("failed to write committed_token_generation", firstLogin.await()?.message)
            assertEquals("failed to write committed_token_generation", duplicateLogin.await()?.message)
            assertEquals(1, authFlow.getAccessTokenCalls)
        }

    @Test
    fun `same intent repository waiter fails pre commit cancellation while old session exists`() =
        runTest(UnconfinedTestDispatcher()) {
            val storage = AuthStorage(
                tokenStore = RecordingTokenStore(
                    accessToken = "old-access-token",
                    refreshToken = "old-refresh-token",
                    idToken = "old-id-token"
                ),
                settings = MapSettings().toSuspendSettings(),
                json = Json { ignoreUnknownKeys = true }
            )
            storage.storeSessionGeneration(1)
            storage.storeCommittedTokenGeneration(1)
            val authFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "new-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(authFlow))
            val repository = oidcRepository(storage)

            val firstLogin = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { repository.login() }
            }
            authFlow.loginStarted.await()

            val duplicateLogin = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { repository.login() }
            }
            assertFalse(duplicateLogin.isCompleted)

            authFlow.loginCanFinish.completeExceptionally(CancellationException("login cancelled before commit"))

            val duplicateResult = withContext(Dispatchers.Default) {
                withTimeout(1_000) {
                    duplicateLogin.await()
                }
            }
            assertTrue(firstLogin.await().isFailure)
            assertTrue(duplicateResult.isFailure)
            assertEquals("old-access-token", repository.getAccessToken())
            assertTrue(repository.isLoggedIn())
        }

    @Test
    fun `local clear still removes tokens when session generation write fails`() =
        runTest(UnconfinedTestDispatcher()) {
            val baseSettings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token"
            )
            val committedStorage = AuthStorage(
                tokenStore = tokenStore,
                settings = baseSettings,
                json = Json { ignoreUnknownKeys = true }
            )
            committedStorage.storeSessionGeneration(1)
            committedStorage.storeCommittedTokenGeneration(1)

            val repository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = FailingSettings(
                        delegate = baseSettings,
                        failKey = "session_generation"
                    ),
                    json = Json { ignoreUnknownKeys = true }
                )
            )

            assertFailsWith<IllegalStateException> {
                repository.clearLocalSession()
            }

            assertNull(tokenStore.getAccessToken())
            assertNull(tokenStore.getRefreshToken())
            assertNull(tokenStore.getIdToken())
            assertNull(
                oidcRepository(
                    AuthStorage(
                        tokenStore = tokenStore,
                        settings = baseSettings,
                        json = Json { ignoreUnknownKeys = true }
                    )
                ).getAccessToken()
            )
        }

    @Test
    fun `local clear hides surviving tokens when generation writes and token removal fail`() =
        runTest(UnconfinedTestDispatcher()) {
            val baseSettings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token",
                failRemoveAccessToken = true
            )
            val committedStorage = AuthStorage(
                tokenStore = tokenStore,
                settings = baseSettings,
                json = Json { ignoreUnknownKeys = true }
            )
            committedStorage.storeSessionGeneration(1)
            committedStorage.storeCommittedTokenGeneration(1)

            val repository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = FailingSettings(
                        delegate = baseSettings,
                        failKeys = setOf(
                            "session_generation"
                        )
                    ),
                    json = Json { ignoreUnknownKeys = true }
                )
            )

            assertFailsWith<IllegalStateException> {
                repository.clearLocalSession()
            }

            assertEquals("old-access-token", tokenStore.getAccessToken())
            val restartedRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = baseSettings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )

            assertNull(restartedRepository.getAccessToken())
            assertFalse(restartedRepository.isLoggedIn())
            assertEquals(emptyMap(), restartedRepository.getAuthHeaders())
        }

    @Test
    fun `repository reauthentication waits for active normal login and starts its own prompt`() =
        runTest(UnconfinedTestDispatcher()) {
            val storage = AuthStorage(
                tokenStore = RecordingTokenStore(),
                settings = MapSettings().toSuspendSettings(),
                json = Json { ignoreUnknownKeys = true }
            )
            val authFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "login-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "login-refresh-token",
                    id_token = "login-id-token"
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(authFlow))
            val repository = oidcRepository(storage)

            val normalLogin = async(start = CoroutineStart.UNDISPATCHED) {
                repository.login()
            }
            withContext(Dispatchers.Default) {
                withTimeout(1_000) {
                    authFlow.loginStarted.await()
                }
            }

            val reauthentication = async(start = CoroutineStart.UNDISPATCHED) {
                repository.loginWithReauthentication()
            }

            authFlow.loginCanFinish.complete(Unit)
            normalLogin.await()
            reauthentication.await()

            assertEquals(2, authFlow.getAccessTokenCalls)
            assertEquals(1, authFlow.promptLoginCalls)
        }

    @Test
    fun `repository reauthentication fails when local clear invalidates active normal login`() =
        runTest(UnconfinedTestDispatcher()) {
            val storage = AuthStorage(
                tokenStore = RecordingTokenStore(),
                settings = MapSettings().toSuspendSettings(),
                json = Json { ignoreUnknownKeys = true }
            )
            val authFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "stale-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(authFlow))
            val repository = oidcRepository(storage)

            val normalLogin = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { repository.login() }
            }
            authFlow.loginStarted.await()

            val reauthentication = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { repository.loginWithReauthentication() }
            }
            assertFalse(reauthentication.isCompleted)

            repository.clearLocalSession()

            val reauthResult = withContext(Dispatchers.Default) {
                withTimeout(1_000) {
                    reauthentication.await()
                }
            }
            assertTrue(reauthResult.isFailure)
            assertEquals(1, authFlow.getAccessTokenCalls)
            assertEquals(0, authFlow.promptLoginCalls)

            authFlow.loginCanFinish.complete(Unit)
            assertTrue(normalLogin.await().isFailure)
        }

    @Test
    fun `superseded active token exchange completes same intent waiters exceptionally`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore()
            val staleAuthFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "stale-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(staleAuthFlow))
            val staleRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )

            val staleLogin = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { staleRepository.login() }
            }
            staleAuthFlow.loginStarted.await()

            val duplicateLogin = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { staleRepository.login() }
            }
            assertFalse(duplicateLogin.isCompleted)

            staleRepository.clearLocalSession()

            val freshAuthFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-fresh-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "fresh-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(freshAuthFlow))

            val freshLogin = async(start = CoroutineStart.UNDISPATCHED) {
                staleRepository.login()
            }
            freshAuthFlow.loginStarted.await()

            val duplicateResult = withContext(Dispatchers.Default) {
                withTimeout(1_000) {
                    duplicateLogin.await()
                }
            }
            assertTrue(duplicateResult.isFailure)

            freshAuthFlow.loginCanFinish.complete(Unit)
            freshLogin.await()

            staleAuthFlow.loginCanFinish.complete(Unit)
            assertTrue(staleLogin.await().isFailure)
        }

    @Test
    fun `local clear completes active repository login waiters`() =
        runTest(UnconfinedTestDispatcher()) {
            val storage = AuthStorage(
                tokenStore = RecordingTokenStore(),
                settings = MapSettings().toSuspendSettings(),
                json = Json { ignoreUnknownKeys = true }
            )
            val authFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "stale-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(authFlow))
            val repository = oidcRepository(storage)

            val login = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { repository.login() }
            }
            authFlow.loginStarted.await()

            val duplicateLogin = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { repository.login() }
            }
            assertFalse(duplicateLogin.isCompleted)

            repository.clearLocalSession()

            val duplicateResult = withContext(Dispatchers.Default) {
                withTimeout(1_000) {
                    duplicateLogin.await()
                }
            }
            assertTrue(duplicateResult.isFailure)

            authFlow.loginCanFinish.complete(Unit)
            assertTrue(login.await().isFailure)
        }

    @Test
    fun `same-process recreated repository does not recover live explicit login marker`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore()
            val authFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "pending-refresh-token",
                    id_token = "pending-id-token"
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "unused-login-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(authFlow))
            val repository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )

            val interruptedLogin = async(start = CoroutineStart.UNDISPATCHED) {
                repository.login()
            }
            withContext(Dispatchers.Default) {
                withTimeout(1_000) {
                    authFlow.loginStarted.await()
                }
            }

            val restartedRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )

            assertNull(restartedRepository.getAccessToken())
            assertNull(tokenStore.getAccessToken())
            assertNull(tokenStore.getRefreshToken())
            assertNull(tokenStore.getIdToken())

            authFlow.loginCanFinish.complete(Unit)
            interruptedLogin.await()
            assertEquals(1, authFlow.getAccessTokenCalls)
        }

    @Test
    fun `stale repository instance cannot commit login tokens after persisted generation moved`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore()
            val authFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "stale-login-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "stale-login-refresh-token",
                    id_token = "stale-login-id-token"
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(authFlow))
            val staleRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )

            val staleLogin = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { staleRepository.login() }
            }
            withContext(Dispatchers.Default) {
                withTimeout(1_000) {
                    authFlow.loginStarted.await()
                }
            }

            val movedRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )
            movedRepository.clearLocalSession()

            authFlow.loginCanFinish.complete(Unit)
            assertTrue(staleLogin.await().isFailure)

            assertNull(tokenStore.getAccessToken())
            assertNull(tokenStore.getRefreshToken())
            assertNull(tokenStore.getIdToken())
        }

    @Test
    fun `same-process repository login shares active exchange across instances`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore()
            val authFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "login-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "login-refresh-token",
                    id_token = "login-id-token"
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(authFlow))
            val firstRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )

            val firstLogin = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { firstRepository.login() }
            }
            withContext(Dispatchers.Default) {
                withTimeout(1_000) {
                    authFlow.loginStarted.await()
                }
            }

            val duplicateRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )

            val duplicateLogin = async(start = CoroutineStart.UNDISPATCHED) {
                duplicateRepository.login()
            }
            assertFalse(duplicateLogin.isCompleted)

            authFlow.loginCanFinish.complete(Unit)
            assertTrue(firstLogin.await().isSuccess)
            duplicateLogin.await()

            assertEquals(1, authFlow.getAccessTokenCalls)
            assertEquals("login-access-token", tokenStore.getAccessToken())
            assertEquals("login-refresh-token", tokenStore.getRefreshToken())
            assertEquals("login-id-token", tokenStore.getIdToken())
        }

    @Test
    fun `stale repository instance cannot commit refresh tokens after persisted generation moved`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token"
            )
            val staleRefreshClient = BlockingRefreshOidcClient(
                refreshResponse = AccessTokenResponse(
                    access_token = "stale-refresh-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "stale-refresh-refresh-token",
                    id_token = "stale-refresh-id-token"
                )
            )
            val staleStorage = AuthStorage(
                tokenStore = tokenStore,
                settings = settings,
                json = Json { ignoreUnknownKeys = true }
            )
            staleStorage.storeSessionGeneration(1)
            staleStorage.storeCommittedTokenGeneration(1)
            val staleRepository = oidcRepository(
                storage = staleStorage,
                oidcClient = staleRefreshClient
            )

            val staleRefresh = async(start = CoroutineStart.UNDISPATCHED) {
                staleRepository.refreshTokensIfNeeded()
            }
            withContext(Dispatchers.Default) {
                withTimeout(1_000) {
                    staleRefreshClient.refreshStarted.await()
                }
            }

            val authFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "fresh-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "fresh-refresh-token",
                    id_token = "fresh-id-token"
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(authFlow))
            val movedRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )
            authFlow.loginCanFinish.complete(Unit)
            movedRepository.login()

            assertEquals("fresh-access-token", tokenStore.getAccessToken())
            assertEquals("fresh-refresh-token", tokenStore.getRefreshToken())
            assertEquals("fresh-id-token", tokenStore.getIdToken())

            staleRefreshClient.refreshCanFinish.complete(Unit)

            assertFalse(staleRefresh.await())
            assertEquals("fresh-access-token", tokenStore.getAccessToken())
            assertEquals("fresh-refresh-token", tokenStore.getRefreshToken())
            assertEquals("fresh-id-token", tokenStore.getIdToken())
        }

    @Test
    fun `stale user info fetch cannot overwrite newer session metadata`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token"
            )
            val staleRefreshClient = BlockingRefreshOidcClient(
                refreshResponse = AccessTokenResponse(
                    access_token = "refreshed-old-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "old-refresh-token",
                    id_token = "old-id-token"
                )
            )
            val staleUserInfo = BlockingUserInfoNetwork(
                userInfo = UserInfo(id = "old-user", name = "Old User")
            )
            val staleStorage = AuthStorage(
                tokenStore = tokenStore,
                settings = settings,
                json = Json { ignoreUnknownKeys = true }
            )
            staleStorage.storeSessionGeneration(1)
            staleStorage.storeCommittedTokenGeneration(1)
            val staleRepository = oidcRepository(
                storage = staleStorage,
                oidcClient = staleRefreshClient,
                networkDataSource = staleUserInfo.networkDataSource()
            )

            val staleRefresh = async(start = CoroutineStart.UNDISPATCHED) {
                staleRepository.refreshTokensIfNeeded()
            }
            withContext(Dispatchers.Default) {
                withTimeout(1_000) {
                    staleRefreshClient.refreshStarted.await()
                }
            }
            staleRefreshClient.refreshCanFinish.complete(Unit)
            withContext(Dispatchers.Default) {
                withTimeout(1_000) {
                    staleUserInfo.fetchStarted.await()
                }
            }

            val freshAuthFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "fresh-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "fresh-refresh-token",
                    id_token = "fresh-id-token"
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(freshAuthFlow))
            val freshUserInfo = BlockingUserInfoNetwork(
                userInfo = UserInfo(id = "fresh-user", name = "Fresh User"),
                initiallyUnblocked = true
            )
            val freshRepository = oidcRepository(
                storage = AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                ),
                networkDataSource = freshUserInfo.networkDataSource()
            )
            freshAuthFlow.loginCanFinish.complete(Unit)
            freshRepository.login()

            assertEquals("fresh-user", freshRepository.getCurrentUser()?.id)

            staleUserInfo.fetchCanFinish.complete(Unit)

            assertFalse(staleRefresh.await())
            assertEquals("fresh-user", freshRepository.getCurrentUser()?.id)
        }

    @Test
    fun `login superseded during user info fetch fails after committed tokens are cleared`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore()
            val authFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "login-access-token",
                    token_type = "Bearer",
                    expires_in = 3600,
                    refresh_token = "login-refresh-token",
                    id_token = "login-id-token"
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(authFlow))
            val userInfo = BlockingUserInfoNetwork(
                userInfo = UserInfo(id = "login-user", name = "Login User")
            )
            val repository = oidcRepository(
                storage = AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                ),
                networkDataSource = userInfo.networkDataSource()
            )

            val login = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { repository.login() }
            }
            authFlow.loginCanFinish.complete(Unit)
            userInfo.fetchStarted.await()

            val clearingRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )
            clearingRepository.clearLocalSession()

            userInfo.fetchCanFinish.complete(Unit)

            assertTrue(login.await().isFailure)
            assertNull(tokenStore.getAccessToken())
        }

    @Test
    fun `user info cancellation after token commit leaves login publishable`() =
        runTest(UnconfinedTestDispatcher()) {
            val storage = AuthStorage(
                tokenStore = RecordingTokenStore(),
                settings = MapSettings().toSuspendSettings(),
                json = Json { ignoreUnknownKeys = true }
            )
            val authFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "login-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(authFlow))
            val userInfo = BlockingUserInfoNetwork(
                userInfo = UserInfo(id = "login-user", name = "Login User"),
                failure = CancellationException("user info canceled")
            )
            val repository = oidcRepository(
                storage = storage,
                networkDataSource = userInfo.networkDataSource()
            )

            val login = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { repository.login() }
            }
            authFlow.loginCanFinish.complete(Unit)
            userInfo.fetchStarted.await()

            val duplicateLogin = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { repository.login() }
            }
            assertFalse(duplicateLogin.isCompleted)

            userInfo.fetchCanFinish.complete(Unit)

            assertTrue(login.await().isSuccess)
            assertTrue(duplicateLogin.await().isSuccess)
            assertEquals("login-access-token", repository.getAccessToken())
            assertTrue(repository.isLoggedIn())
        }

    @Test
    fun `same intent repository waiter succeeds after post commit caller cancellation`() =
        runTest(UnconfinedTestDispatcher()) {
            val storage = AuthStorage(
                tokenStore = RecordingTokenStore(),
                settings = MapSettings().toSuspendSettings(),
                json = Json { ignoreUnknownKeys = true }
            )
            val authFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "login-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(authFlow))
            val userInfo = BlockingUserInfoNetwork(
                userInfo = UserInfo(id = "login-user", name = "Login User")
            )
            val repository = oidcRepository(
                storage = storage,
                networkDataSource = userInfo.networkDataSource()
            )

            val firstLogin = async(start = CoroutineStart.UNDISPATCHED) {
                repository.login()
            }
            authFlow.loginCanFinish.complete(Unit)
            userInfo.fetchStarted.await()

            val duplicateLogin = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { repository.login() }
            }
            assertFalse(duplicateLogin.isCompleted)

            firstLogin.cancel()
            userInfo.fetchCanFinish.complete(Unit)

            val duplicateResult = withContext(Dispatchers.Default) {
                withTimeout(1_000) {
                    duplicateLogin.await()
                }
            }
            assertTrue(duplicateResult.isSuccess)
            assertEquals("login-access-token", repository.getAccessToken())
            assertTrue(repository.isLoggedIn())
        }

    @Test
    fun `same intent repository waiter succeeds after post commit cancellation with reused token`() =
        runTest(UnconfinedTestDispatcher()) {
            val storage = AuthStorage(
                tokenStore = RecordingTokenStore(
                    accessToken = "same-access-token",
                    refreshToken = "old-refresh-token",
                    idToken = "old-id-token"
                ),
                settings = MapSettings().toSuspendSettings(),
                json = Json { ignoreUnknownKeys = true }
            )
            storage.storeSessionGeneration(1)
            storage.storeCommittedTokenGeneration(1)
            val authFlow = BlockingLoginAuthFlow(
                pendingResponse = AccessTokenResponse(
                    access_token = "unused-pending-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                ),
                loginResponse = AccessTokenResponse(
                    access_token = "same-access-token",
                    token_type = "Bearer",
                    expires_in = 3600
                )
            )
            AuthFlowFactoryProvider.initialize(StaticCodeAuthFlowFactory(authFlow))
            val userInfo = BlockingUserInfoNetwork(
                userInfo = UserInfo(id = "login-user", name = "Login User")
            )
            val repository = oidcRepository(
                storage = storage,
                networkDataSource = userInfo.networkDataSource()
            )

            val firstLogin = async(start = CoroutineStart.UNDISPATCHED) {
                repository.login()
            }
            authFlow.loginCanFinish.complete(Unit)
            userInfo.fetchStarted.await()

            val duplicateLogin = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { repository.login() }
            }
            assertFalse(duplicateLogin.isCompleted)

            firstLogin.cancel()
            userInfo.fetchCanFinish.complete(Unit)

            val duplicateResult = withContext(Dispatchers.Default) {
                withTimeout(1_000) {
                    duplicateLogin.await()
                }
            }
            assertTrue(duplicateResult.isSuccess)
            assertEquals("same-access-token", repository.getAccessToken())
            assertTrue(repository.isLoggedIn())
        }

    @Test
    fun `loaded repository hides surviving tokens after another instance persisted logout invalidation`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token",
                failRemoveAccessToken = true
            )
            val loadedStorage = AuthStorage(
                tokenStore = tokenStore,
                settings = settings,
                json = Json { ignoreUnknownKeys = true }
            )
            loadedStorage.storeSessionGeneration(1)
            loadedStorage.storeCommittedTokenGeneration(1)
            val loadedRepository = oidcRepository(loadedStorage)

            assertEquals("old-access-token", loadedRepository.getAccessToken())

            val invalidatingRepository = oidcRepository(
                AuthStorage(
                    tokenStore = tokenStore,
                    settings = settings,
                    json = Json { ignoreUnknownKeys = true }
                )
            )
            assertFailsWith<IllegalStateException> {
                invalidatingRepository.clearLocalSession()
            }
            assertEquals("old-access-token", tokenStore.getAccessToken())

            assertNull(loadedRepository.getAccessToken())
            assertFalse(loadedRepository.isLoggedIn())
            assertEquals(emptyMap(), loadedRepository.getAuthHeaders())
        }

    @Test
    fun `publishable metadata and token material read block concurrent persisted invalidation`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MapSettings().toSuspendSettings()
            val tokenStore = RecordingTokenStore(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token",
                failRemoveAccessToken = true,
                suspendAccessTokenReadOnCall = 1
            )
            val loadedStorage = AuthStorage(
                tokenStore = tokenStore,
                settings = settings,
                json = Json { ignoreUnknownKeys = true }
            )
            loadedStorage.storeSessionGeneration(1)
            loadedStorage.storeCommittedTokenGeneration(1)
            val loadedRepository = oidcRepository(loadedStorage)

            val tokenRead = async(start = CoroutineStart.UNDISPATCHED) {
                loadedRepository.getAccessToken()
            }
            tokenStore.accessTokenReadStarted.await()

            val clear = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    oidcRepository(
                        AuthStorage(
                            tokenStore = tokenStore,
                            settings = settings,
                            json = Json { ignoreUnknownKeys = true }
                        )
                    ).clearLocalSession()
                }
            }

            assertFalse(clear.isCompleted)

            tokenStore.accessTokenReadCanFinish.complete(Unit)

            assertEquals("old-access-token", tokenRead.await())
            assertTrue(clear.await().isFailure)
            assertNull(loadedRepository.getAccessToken())
        }
}

private fun oidcRepository(
    storage: AuthStorage,
    oidcClient: OpenIdConnectClient = BlockingRefreshOidcClient(
        refreshResponse = AccessTokenResponse(
            access_token = "unused-access-token",
            token_type = "Bearer",
            expires_in = 3600
        )
    ),
    networkDataSource: AuthNetworkDataSource = defaultAuthNetworkDataSource()
): OidcAuthRepository =
    OidcAuthRepository(
        authConfig = AuthConfig(clientId = "client-id"),
        authStorage = storage,
        oidcClient = oidcClient,
        networkDataSource = networkDataSource
    )

private fun defaultAuthNetworkDataSource(): AuthNetworkDataSource =
    AuthNetworkDataSource(
        authConfig = AuthConfig(clientId = "client-id"),
        httpClient = HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine {
                addHandler {
                    respond(
                        content = """{"sub":"default-user"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString())
                    )
                }
            }
        }
    )

private class BlockingUserInfoNetwork(
    private val userInfo: UserInfo,
    initiallyUnblocked: Boolean = false,
    private val failure: Exception? = null
) {
    val fetchStarted = CompletableDeferred<Unit>()
    val fetchCanFinish = CompletableDeferred<Unit>().apply {
        if (initiallyUnblocked) {
            complete(Unit)
        }
    }

    fun networkDataSource(): AuthNetworkDataSource =
        AuthNetworkDataSource(
            authConfig = AuthConfig(clientId = "client-id"),
            httpClient = HttpClient(MockEngine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
                engine {
                    addHandler {
                        fetchStarted.complete(Unit)
                        fetchCanFinish.await()
                        failure?.let { throw it }
                        respond(
                            content = """{"sub":"${userInfo.id}","name":"${userInfo.name.orEmpty()}"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", ContentType.Application.Json.toString())
                        )
                    }
                }
            }
        )
}

private class BlockingLoginAuthFlow(
    private val pendingResponse: AccessTokenResponse,
    private val loginResponse: AccessTokenResponse
) : CodeAuthFlow {
    val continueStarted = CompletableDeferred<Unit>()
    val continueCanFinish = CompletableDeferred<Unit>()
    val loginStarted = CompletableDeferred<Unit>()
    val loginCanFinish = CompletableDeferred<Unit>()
    var getAccessTokenCalls = 0
        private set
    var continueLoginCalls = 0
        private set
    var promptLoginCalls = 0
        private set

    override suspend fun getAccessToken(
        configureAuthUrl: (URLBuilder.() -> Unit)?,
        configureTokenExchange: (HttpRequestBuilder.() -> Unit)?
    ): AccessTokenResponse {
        getAccessTokenCalls += 1
        val authUrl = URLBuilder()
        configureAuthUrl?.invoke(authUrl)
        if (authUrl.parameters["prompt"] == "login") {
            promptLoginCalls += 1
        }
        loginStarted.complete(Unit)
        loginCanFinish.await()
        return loginResponse
    }

    override suspend fun startLogin(configureAuthUrl: (URLBuilder.() -> Unit)?): AuthCodeRequest =
        unsupportedAuthTest()

    override suspend fun canContinueLogin(): Boolean = true

    override suspend fun continueLogin(
        configureTokenExchange: (HttpRequestBuilder.() -> Unit)?
    ): AccessTokenResponse {
        continueLoginCalls += 1
        continueStarted.complete(Unit)
        continueCanFinish.await()
        return pendingResponse
    }
}

private object NoPendingLoginAuthFlow : CodeAuthFlow {
    override suspend fun startLogin(configureAuthUrl: (URLBuilder.() -> Unit)?): AuthCodeRequest =
        unsupportedAuthTest()

    override suspend fun canContinueLogin(): Boolean = false

    override suspend fun continueLogin(
        configureTokenExchange: (HttpRequestBuilder.() -> Unit)?
    ): AccessTokenResponse = unsupportedAuthTest()
}

private class FailingLoginAuthFlow(
    private val failure: Exception
) : CodeAuthFlow {
    override suspend fun getAccessToken(
        configureAuthUrl: (URLBuilder.() -> Unit)?,
        configureTokenExchange: (HttpRequestBuilder.() -> Unit)?
    ): AccessTokenResponse {
        throw failure
    }

    override suspend fun startLogin(configureAuthUrl: (URLBuilder.() -> Unit)?): AuthCodeRequest =
        unsupportedAuthTest()

    override suspend fun canContinueLogin(): Boolean = false

    override suspend fun continueLogin(
        configureTokenExchange: (HttpRequestBuilder.() -> Unit)?
    ): AccessTokenResponse = unsupportedAuthTest()
}

private class StaticCodeAuthFlowFactory(
    private val authFlow: CodeAuthFlow
) : CodeAuthFlowFactory {
    override fun createAuthFlow(client: OpenIdConnectClient): CodeAuthFlow = authFlow

    override fun createEndSessionFlow(client: OpenIdConnectClient): EndSessionFlow =
        NoOpEndSessionFlow
}

private object CancellingEndSessionFlowFactory : CodeAuthFlowFactory {
    override fun createAuthFlow(client: OpenIdConnectClient): CodeAuthFlow = unsupportedAuthTest()

    override fun createEndSessionFlow(client: OpenIdConnectClient): EndSessionFlow =
        object : EndSessionFlow {
            override suspend fun startLogout(
                idToken: String?,
                configureEndSessionUrl: (URLBuilder.() -> Unit)?
            ) {
                throw CancellationException("end session cancelled")
            }

            override suspend fun canContinueLogout(): Boolean = false

            override suspend fun continueLogout() = Unit
        }
}

private object NoOpEndSessionFlow : EndSessionFlow {
    override suspend fun startLogout(
        idToken: String?,
        configureEndSessionUrl: (URLBuilder.() -> Unit)?
    ) = Unit

    override suspend fun canContinueLogout(): Boolean = false

    override suspend fun continueLogout() = Unit
}

private fun unsupportedAuthTest(): Nothing =
    throw UnsupportedOperationException("Not needed for this test")

private class FailingSettings(
    private val delegate: SuspendSettings,
    failKey: String? = null,
    private val failKeys: Set<String> = failKey?.let(::setOf).orEmpty(),
    private val failRemoveKeys: Set<String> = emptySet()
) : SuspendSettings {
    override suspend fun keys(): Set<String> = delegate.keys()
    override suspend fun size(): Int = delegate.size()
    override suspend fun clear() = delegate.clear()
    override suspend fun remove(key: String) {
        if (key in failRemoveKeys) {
            throw IllegalStateException("failed to remove $key")
        }
        delegate.remove(key)
    }
    override suspend fun hasKey(key: String): Boolean = delegate.hasKey(key)
    override suspend fun putInt(key: String, value: Int) = delegate.putInt(key, value)
    override suspend fun getInt(key: String, defaultValue: Int): Int = delegate.getInt(key, defaultValue)
    override suspend fun getIntOrNull(key: String): Int? = delegate.getIntOrNull(key)
    override suspend fun putLong(key: String, value: Long) {
        if (key in failKeys) {
            throw IllegalStateException("failed to write $key")
        }
        delegate.putLong(key, value)
    }
    override suspend fun getLong(key: String, defaultValue: Long): Long = delegate.getLong(key, defaultValue)
    override suspend fun getLongOrNull(key: String): Long? = delegate.getLongOrNull(key)
    override suspend fun putString(key: String, value: String) = delegate.putString(key, value)
    override suspend fun getString(key: String, defaultValue: String): String =
        delegate.getString(key, defaultValue)
    override suspend fun getStringOrNull(key: String): String? = delegate.getStringOrNull(key)
    override suspend fun putFloat(key: String, value: Float) = delegate.putFloat(key, value)
    override suspend fun getFloat(key: String, defaultValue: Float): Float = delegate.getFloat(key, defaultValue)
    override suspend fun getFloatOrNull(key: String): Float? = delegate.getFloatOrNull(key)
    override suspend fun putDouble(key: String, value: Double) = delegate.putDouble(key, value)
    override suspend fun getDouble(key: String, defaultValue: Double): Double =
        delegate.getDouble(key, defaultValue)
    override suspend fun getDoubleOrNull(key: String): Double? = delegate.getDoubleOrNull(key)
    override suspend fun putBoolean(key: String, value: Boolean) = delegate.putBoolean(key, value)
    override suspend fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        delegate.getBoolean(key, defaultValue)
    override suspend fun getBooleanOrNull(key: String): Boolean? = delegate.getBooleanOrNull(key)
}

private class BlockingRefreshOidcClient(
    private val refreshResponse: AccessTokenResponse,
    private val refreshFailure: Exception? = null,
    private val revokeFailure: Exception? = null
) : OpenIdConnectClient {
    val refreshStarted = CompletableDeferred<Unit>()
    val refreshCanFinish = CompletableDeferred<Unit>()

    override val config: OpenIdConnectClientConfig = OpenIdConnectClientConfig {
        clientId = "client-id"
        redirectUri = "com.quran.oauth://callback"
        endpoints {
            authorizationEndpoint = "https://example.com/auth"
            tokenEndpoint = "https://example.com/token"
        }
    }
    override val discoverDocument: OpenIdConnectConfiguration? = null

    override fun createAuthorizationCodeRequest(configure: (URLBuilder.() -> Unit)?): AuthCodeRequest =
        unsupported()

    override fun createEndSessionRequest(
        idToken: String?,
        configure: (URLBuilder.() -> Unit)?
    ): EndSessionRequest =
        unsupported()

    override suspend fun discover(configure: (HttpRequestBuilder.() -> Unit)?) = Unit

    override suspend fun endSession(
        idToken: String,
        configure: (HttpRequestBuilder.() -> Unit)?
    ): HttpStatusCode = unsupported()

    override suspend fun revokeToken(
        token: String,
        configure: (HttpRequestBuilder.() -> Unit)?
    ): HttpStatusCode {
        revokeFailure?.let { throw it }
        return unsupported()
    }

    override suspend fun exchangeToken(
        authCodeRequest: AuthCodeRequest,
        code: String,
        configure: (HttpRequestBuilder.() -> Unit)?
    ): AccessTokenResponse = unsupported()

    override suspend fun refreshToken(
        refreshToken: String,
        configure: (HttpRequestBuilder.() -> Unit)?
    ): AccessTokenResponse {
        refreshStarted.complete(Unit)
        refreshCanFinish.await()
        refreshFailure?.let { throw it }
        return refreshResponse
    }

    override suspend fun createAccessTokenRequest(
        authCodeRequest: AuthCodeRequest,
        code: String,
        configure: (HttpRequestBuilder.() -> Unit)?
    ): TokenRequest = unsupported()

    override suspend fun createRefreshTokenRequest(
        refreshToken: String,
        configure: (HttpRequestBuilder.() -> Unit)?
    ): TokenRequest = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("Not needed for this test")
}

private class RecordingTokenStore(
    accessToken: String? = null,
    refreshToken: String? = null,
    idToken: String? = null,
    private val failRemoveAccessToken: Boolean = false,
    private val cancelBeforeSaveTokens: Boolean = false,
    private val cancelAfterPartialSaveTokens: Boolean = false,
    private val suspendAccessTokenReadOnCall: Int? = null
) : TokenStore() {
    private val accessTokenState = MutableStateFlow(accessToken)
    private val refreshTokenState = MutableStateFlow(refreshToken)
    private val idTokenState = MutableStateFlow(idToken)

    private var accessToken: String? = accessToken
    private var refreshToken: String? = refreshToken
    private var idToken: String? = idToken
    private var saveTokensCalls = 0
    private var accessTokenReadCalls = 0
    val accessTokenReadStarted = CompletableDeferred<Unit>()
    val accessTokenReadCanFinish = CompletableDeferred<Unit>()

    override val accessTokenFlow: StateFlow<String?> = accessTokenState
    override val refreshTokenFlow: StateFlow<String?> = refreshTokenState
    override val idTokenFlow: StateFlow<String?> = idTokenState

    override suspend fun getAccessToken(): String? {
        accessTokenReadCalls += 1
        if (accessTokenReadCalls == suspendAccessTokenReadOnCall) {
            accessTokenReadStarted.complete(Unit)
            accessTokenReadCanFinish.await()
        }
        return accessToken
    }

    override suspend fun getRefreshToken(): String? = refreshToken

    override suspend fun getIdToken(): String? = idToken

    override suspend fun removeAccessToken() {
        if (failRemoveAccessToken) {
            throw IllegalStateException("access token clear failed")
        }
        accessToken = null
        accessTokenState.value = null
    }

    override suspend fun removeRefreshToken() {
        refreshToken = null
        refreshTokenState.value = null
    }

    override suspend fun removeIdToken() {
        idToken = null
        idTokenState.value = null
    }

    override suspend fun saveTokens(accessToken: String, refreshToken: String?, idToken: String?) {
        saveTokensCalls += 1
        if (cancelBeforeSaveTokens && saveTokensCalls == 1) {
            throw CancellationException("token save canceled")
        }
        if (cancelAfterPartialSaveTokens && saveTokensCalls == 1) {
            this.accessToken = accessToken
            accessTokenState.value = accessToken
            throw CancellationException("token save canceled after access token")
        }
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        this.idToken = idToken
        accessTokenState.value = accessToken
        refreshTokenState.value = refreshToken
        idTokenState.value = idToken
    }
}
