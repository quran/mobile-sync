package com.quran.shared.pipeline

import app.cash.sqldelight.db.SqlDriver
import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.auth.repository.OidcAuthRepository
import com.quran.shared.auth.service.AuthService
import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.repository.PersistenceResetRepositoryImpl
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepositoryImpl
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.repository.note.repository.NotesRepositoryImpl
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepositoryImpl
import com.quran.shared.pipeline.sync.SyncClient
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.SuspendSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createDynamicGraphFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.publicvalue.multiplatform.oidc.DefaultOpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectClientConfig
import org.publicvalue.multiplatform.oidc.tokenstore.SettingsStore
import org.publicvalue.multiplatform.oidc.tokenstore.SettingsTokenStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [QuranDataService.clearAndJoin] with the production graph pieces, the real auth stack and the real sync client over a
 * mock network. The profile fetch is held where cancelling it can't stop it at once: a storage write already under
 * way. (A request held at the HTTP boundary doesn't hold the fetch: the client abandons it when the fetch is cancelled.)
 */
class QuranDataServiceProfileTest {
    private val mainThread = Executors.newSingleThreadExecutor { Thread(it, "test-main").apply { isDaemon = true } }
    private val authConfig = AuthConfig(clientId = "client-id")
    private val settings = HeldAttemptWrites(MapSettings().toSuspendSettings())
    private val storage = AuthStorage(SettingsTokenStore(InMemorySettingsStore()), settings, Json)
    private val sessionUser = UserInfo(id = ACCOUNT, name = "Old Name")
    private lateinit var driver: SqlDriver
    private lateinit var authService: AuthService
    private lateinit var service: QuranDataService

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainThread.asCoroutineDispatcher())
    }

    @AfterTest
    fun tearDown() {
        settings.writeCanFinish.complete(Unit)
        runBlocking {
            if (::service.isInitialized) service.clearAndJoin()
            if (::authService.isInitialized) authService.clearAndJoin()
        }
        if (::driver.isInitialized) driver.close()
        Dispatchers.resetMain()
        mainThread.shutdownNow()
    }

    @Test
    fun `clearAndJoin waits for the profile fetch, which writes nothing after it`() = runBlocking {
        start()
        launch(Dispatchers.Default) { runCatching { service.refreshProfile() } }
        withTimeout(10.seconds) { settings.writeStarted.await() }

        val clearing = async(Dispatchers.Default) { service.clearAndJoin() }
        delay(300.milliseconds)
        assertFalse(clearing.isCompleted, "clearAndJoin returned while the profile fetch was still writing")
        settings.writeCanFinish.complete(Unit)
        withTimeout(10.seconds) { clearing.await() }
        val writesAtReturn = settings.writes.get()
        delay(300.milliseconds)

        assertEquals(writesAtReturn, settings.writes.get(), "the fetch wrote after clearAndJoin returned")
        assertNull(storage.retrieveFetchedProfile(), "the cancelled fetch stored a profile")
        assertEquals(AuthState.Success(sessionUser), authService.authState.value)
    }

    private suspend fun start() {
        signIn()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/auth/v1/users/profile" ->
                    respond(PROFILE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                "/auth/v1/sync" -> respond(EMPTY_SYNC, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> error("Unexpected request to ${request.url}")
            }
        }
        val oidcClient = DefaultOpenIdConnectClient(
            httpClient = HttpClient(engine),
            config = OpenIdConnectClientConfig {
                endpoints {
                    authorizationEndpoint = authConfig.authorizationEndpoint
                    tokenEndpoint = authConfig.tokenEndpoint
                }
                clientId = authConfig.clientId
                redirectUri = authConfig.redirectUri
            }
        )
        authService = withContext(Dispatchers.Main) { AuthService(OidcAuthRepository(authConfig, storage, oidcClient)) }
        withTimeout(10.seconds) {
            while (authService.authState.value !is AuthState.Success) delay(1)
        }
        val database = inMemoryMobileSyncDatabase()
        driver = database.driver
        val synced = database.synced
        service = createDynamicGraphFactory<QuranDataServiceTestGraph.Factory>(MockNetworkSyncBindings(database, engine))
            .create(
                authService = authService,
                pipeline = SyncEnginePipeline(
                    bookmarksRepository = BookmarksRepositoryImpl(synced),
                    readingBookmarksRepository = ReadingBookmarksRepositoryImpl(synced),
                    collectionsRepository = CollectionsRepositoryImpl(synced),
                    collectionBookmarksRepository = CollectionBookmarksRepositoryImpl(synced),
                    notesRepository = NotesRepositoryImpl(synced),
                    readingSessionsRepository = ReadingSessionsRepositoryImpl(synced)
                ),
                environment = SynchronizationEnvironment(
                    "https://apis.quran.foundation/auth",
                    avatarsHost = "https://avatars.example.invalid"
                ),
                persistenceResetRepository = PersistenceResetRepositoryImpl(synced),
                persistenceImportRepository = PersistenceImportRepositoryImpl(database.quran, synced),
                sessionLifecycleCoordinator = SessionLifecycleCoordinator(
                    SettingsSessionLifecycleStateStore(MapSettings().toSuspendSettings())
                ),
                database = synced
            ).quranDataService
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

    /** An unsigned ID token for [ACCOUNT] from this client's issuer. */
    private fun idToken(): String {
        val payload = buildJsonObject {
            put("sub", ACCOUNT)
            put("iss", authConfig.baseUrl)
            put("aud", buildJsonArray { add(JsonPrimitive(authConfig.clientId)) })
        }
        val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        return listOf("""{"alg":"RS256","typ":"JWT"}""", payload.toString(), "signature")
            .joinToString(".") { base64.encode(it.encodeToByteArray()) }
    }

    private companion object {
        const val ACCOUNT = "6f1c2d3e-4a5b-4c6d-8e7f-90a1b2c3d4e5"
        const val PROFILE = """{"success":true,"data":{"id":"$ACCOUNT","firstName":"New","lastName":"Name","fullName":"New Name"}}"""
        const val EMPTY_SYNC = """{"success":true,"data":{"lastMutationAt":0,"mutations":[],"hasMore":false}}"""
    }
}

/** Runs the production sync client over [engine], this test's network. */
@BindingContainer
private class MockNetworkSyncBindings(private val database: MobileSyncDatabase, private val engine: HttpClientEngine) {
    @Provides
    fun synchronizationClientFactory(): QuranDataServiceSynchronizationClientFactory =
        QuranDataServiceSynchronizationClientFactory { environment, account, gate, observer ->
            SyncClient.create(database.synced, environment, account, gate, observer, engine = engine)
        }
}

/**
 * Settings that hold the first write of the profile fetch's attempt time until [writeCanFinish] completes, and finish it
 * even if the writer is cancelled meanwhile, as a storage write already under way does. [writes] counts every write.
 */
private class HeldAttemptWrites(private val delegate: SuspendSettings) : SuspendSettings by delegate {
    val writeStarted = CompletableDeferred<Unit>()
    val writeCanFinish = CompletableDeferred<Unit>()
    val writes = AtomicInteger()

    override suspend fun putLong(key: String, value: Long) {
        writes.incrementAndGet()
        if (key == "profile_fetch_attempted_at" && writeStarted.complete(Unit)) {
            withContext(NonCancellable) {
                writeCanFinish.await()
                delegate.putLong(key, value)
            }
            return
        }
        delegate.putLong(key, value)
    }

    override suspend fun putString(key: String, value: String) {
        writes.incrementAndGet()
        delegate.putString(key, value)
    }
}

private class InMemorySettingsStore : SettingsStore {
    private val values = mutableMapOf<String, String>()
    override suspend fun get(key: String): String? = values[key]
    override suspend fun put(key: String, value: String) { values[key] = value }
    override suspend fun remove(key: String) { values.remove(key) }
    override suspend fun clear() = values.clear()
}
