package com.quran.shared.auth.service

import com.quran.shared.auth.TEST_SUBJECT
import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.model.FetchedProfile
import com.quran.shared.auth.model.FetchedProfileWrite
import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.BlockingRefreshOidcClient
import com.quran.shared.auth.repository.RecordingTokenStore
import com.quran.shared.auth.repository.oidcRepository
import com.quran.shared.auth.testIdToken
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A fetched profile reaches [AuthService.authState], once per change, and outlasts token refreshes. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthServiceFetchedProfileTest {
    /** [AuthService] runs its own work on Main. */
    private val main = StandardTestDispatcher()
    private val settings = MapSettings().toSuspendSettings(main)
    private val storage = AuthStorage(RecordingTokenStore(), settings, Json { ignoreUnknownKeys = true })
    private val sessionUser = UserInfo(id = TEST_SUBJECT, name = "Old Name", email = "user@example.com")
    private val renamed = FetchedProfile(
        id = TEST_SUBJECT,
        name = "New Name",
        email = "user@example.com",
        photoUrl = "https://example.com/photo.png",
        fetchedAt = 1_000L
    )
    private val renamedUser = UserInfo(
        id = TEST_SUBJECT,
        name = "New Name",
        email = "user@example.com",
        photoUrl = "https://example.com/photo.png"
    )

    /** Refreshes with an ID token that still has the name the user consented with. */
    private val oldNameRefresh = BlockingRefreshOidcClient(
        refreshResponse = AccessTokenResponse(
            access_token = "refreshed-access-token",
            token_type = "Bearer",
            expires_in = 3600,
            id_token = testIdToken("refreshed-id-token", claims = mapOf("name" to "Old Name", "email" to "user@example.com"))
        )
    ).also { it.refreshCanFinish.complete(Unit) }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a stored profile is published once`() = runTest(main) {
        val service = signedInService()
        val states = recordStates(service)
        val session = assertNotNull(service.profileFetchSession())

        assertEquals(FetchedProfileWrite.STORED, service.storeFetchedProfile(session, renamed))
        assertEquals(FetchedProfileWrite.STORED, service.storeFetchedProfile(session, renamed.copy(fetchedAt = 2_000L)))
        advanceUntilIdle()

        assertEquals(listOf<AuthState>(AuthState.Success(sessionUser), AuthState.Success(renamedUser)), states)
        service.clearAndJoin()
    }

    @Test
    fun `a token refresh does not bring back the old name`() = runTest(main) {
        val service = signedInService()
        service.storeFetchedProfile(assertNotNull(service.profileFetchSession()), renamed)
        val states = recordStates(service)
        settings.putLong("token_expiration", 0L)

        val headers = service.getAuthHeaders()
        advanceUntilIdle()

        assertEquals("Bearer refreshed-access-token", headers["Authorization"])
        assertEquals(listOf<AuthState>(AuthState.Success(renamedUser)), states)
        service.clearAndJoin()
    }

    /**
     * A sync request's republish reads the session before the profile is stored and publishes after the store's own
     * republish: the older read must not bring back the old name.
     */
    @Test
    fun `a session read before a profile was stored does not publish over it`() = runTest(main) {
        val repository = HeldUserReads(oidcRepository(storage, oldNameRefresh))
        val service = signedInService(repository)
        val session = assertNotNull(service.profileFetchSession())
        val states = recordStates(service)
        settings.putLong("token_expiration", 0L)
        val readCanFinish = CompletableDeferred<Unit>()
        repository.holdNextUserRead = readCanFinish

        val headers = async { service.getAuthHeaders() }
        runCurrent()
        assertTrue(repository.userRead.isCompleted, "the refreshed session's republish has read the user")
        val store = async { service.storeFetchedProfile(session, renamed) }
        runCurrent()
        readCanFinish.complete(Unit)
        assertEquals("Bearer refreshed-access-token", headers.await()["Authorization"])
        assertEquals(FetchedProfileWrite.STORED, store.await())
        advanceUntilIdle()

        assertEquals(AuthState.Success(renamedUser), service.authState.value)
        assertEquals(AuthState.Success(renamedUser), states.last(), "$states")
        service.clearAndJoin()
    }

    @Test
    fun `another account's profile is dropped and the session kept`() = runTest(main) {
        val service = signedInService()
        val session = assertNotNull(service.profileFetchSession())

        val write = service.storeFetchedProfile(session, renamed.copy(id = "someone-else"))

        assertEquals(FetchedProfileWrite.OTHER_ACCOUNT, write)
        assertEquals(AuthState.Success(sessionUser), service.authState.value)
        service.clearAndJoin()
    }

    @Test
    fun `a profile fetched across a logout is not published`() = runTest(main) {
        val service = signedInService()
        val session = assertNotNull(service.profileFetchSession())
        service.clearLocalSession()

        val write = service.storeFetchedProfile(session, renamed)
        advanceUntilIdle()

        assertEquals(FetchedProfileWrite.SESSION_CHANGED, write)
        assertEquals(AuthState.Idle, service.authState.value)
        assertNull(storage.retrieveFetchedProfile())
        service.clearAndJoin()
    }

    @Test
    fun `signed out there is no session to fetch for`() = runTest(main) {
        val service = AuthService(oidcRepository(storage, oldNameRefresh))
        advanceUntilIdle()

        assertNull(service.profileFetchSession())
        service.clearAndJoin()
    }

    /** A service whose stored session for [sessionUser] it has published. */
    private suspend fun TestScope.signedInService(
        repository: AuthRepository = oidcRepository(storage, oldNameRefresh)
    ): AuthService {
        val generation = storage.retrieveSessionGeneration() + 1
        storage.storeNewSessionTokens(
            TokenResponse(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                idToken = testIdToken("login-id-token"),
                expiresIn = 3600,
                tokenType = "Bearer"
            ),
            sessionUser
        )
        storage.storeSessionGeneration(generation)
        storage.storeCommittedTokenGeneration(generation)
        val service = AuthService(repository)
        advanceUntilIdle()
        assertEquals(AuthState.Success(sessionUser), service.authState.value)
        return service
    }

    private fun TestScope.recordStates(service: AuthService): List<AuthState> {
        val states = mutableListOf<AuthState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            service.authState.toList(states)
        }
        return states
    }
}

/**
 * The real repository, with the next [getCurrentUser] held after it has read the user and before it returns: the gap
 * between a publish's read and its publication, where a newer publish can land.
 */
private class HeldUserReads(private val repository: AuthRepository) : AuthRepository by repository {
    var holdNextUserRead: CompletableDeferred<Unit>? = null
    val userRead = CompletableDeferred<Unit>()

    override suspend fun getCurrentUser(): UserInfo? {
        val user = repository.getCurrentUser()
        holdNextUserRead?.let { hold ->
            holdNextUserRead = null
            userRead.complete(Unit)
            hold.await()
        }
        return user
    }
}
