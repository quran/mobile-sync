package com.quran.shared.auth.repository

import com.quran.shared.auth.TEST_SUBJECT
import com.quran.shared.auth.model.FetchedProfile
import com.quran.shared.auth.model.FetchedProfileWrite
import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.auth.testIdToken
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A fetched profile is stored with the session that fetched it, under the same session-generation guard as a token
 * refresh, and shows over the ID token's user until the session ends.
 */
class OidcAuthRepositoryFetchedProfileTest {
    private val storage = AuthStorage(RecordingTokenStore(), MapSettings().toSuspendSettings(), Json { ignoreUnknownKeys = true })
    private val sessionUser = UserInfo(id = TEST_SUBJECT, name = "Old Name", email = "user@example.com")
    private val renamed = FetchedProfile(
        id = TEST_SUBJECT,
        name = "New Name",
        firstName = "New",
        lastName = "Name",
        email = "user@example.com",
        photoUrl = "https://example.com/photo.png",
        fetchedAt = 1_000L
    )
    private val renamedUser = UserInfo(
        id = TEST_SUBJECT,
        firstName = "New",
        lastName = "Name",
        name = "New Name",
        email = "user@example.com",
        photoUrl = "https://example.com/photo.png"
    )

    @Test
    fun `without a session there is nothing to fetch for`() = runTest {
        assertNull(oidcRepository(storage).captureProfileFetchSession())
    }

    @Test
    fun `a stored profile shows over the ID token's user`() = runTest {
        val repository = oidcRepository(storage)
        signIn()
        val session = assertNotNull(repository.captureProfileFetchSession())
        assertEquals(TEST_SUBJECT, session.accountId)
        assertNull(session.fetchedAt)

        assertEquals(FetchedProfileWrite.STORED, repository.storeFetchedProfile(session, renamed))

        assertEquals(renamedUser, repository.getCurrentUser())
        assertEquals(1_000L, repository.captureProfileFetchSession()?.fetchedAt)
    }

    @Test
    fun `a token refresh does not bring back the old name`() = runTest {
        val client = BlockingRefreshOidcClient(
            refreshResponse = AccessTokenResponse(
                access_token = "refreshed-access-token",
                token_type = "Bearer",
                expires_in = 3600,
                id_token = testIdToken("refreshed-id-token", claims = mapOf("name" to "Old Name"))
            )
        ).also { it.refreshCanFinish.complete(Unit) }
        val repository = oidcRepository(storage, client)
        signIn()
        repository.storeFetchedProfile(assertNotNull(repository.captureProfileFetchSession()), renamed)

        assertTrue(repository.forceRefreshTokens())

        assertEquals("refreshed-access-token", repository.getAccessToken())
        assertEquals(renamedUser, repository.getCurrentUser())
    }

    @Test
    fun `another account's profile is dropped and the session kept`() = runTest {
        val repository = oidcRepository(storage)
        signIn()
        val session = assertNotNull(repository.captureProfileFetchSession())

        val write = repository.storeFetchedProfile(session, renamed.copy(id = "someone-else"))

        assertEquals(FetchedProfileWrite.OTHER_ACCOUNT, write)
        assertEquals(sessionUser, repository.getCurrentUser())
        assertTrue(repository.isLoggedIn())
        assertNull(storage.retrieveFetchedProfile())
    }

    @Test
    fun `a profile fetched across a logout and sign-in writes nothing`() = runTest {
        val repository = oidcRepository(storage)
        signIn()
        val session = assertNotNull(repository.captureProfileFetchSession())
        repository.clearLocalSession()
        signIn()

        val write = repository.storeFetchedProfile(session, renamed)

        assertEquals(FetchedProfileWrite.SESSION_CHANGED, write)
        assertEquals(sessionUser, repository.getCurrentUser())
        assertNull(storage.retrieveFetchedProfile())
    }

    @Test
    fun `a profile fetched across a sign-in to another account writes nothing`() = runTest {
        val repository = oidcRepository(storage)
        signIn()
        val session = assertNotNull(repository.captureProfileFetchSession())
        signIn(UserInfo(id = "other-account", name = "Other"))

        val write = repository.storeFetchedProfile(session, renamed)

        assertEquals(FetchedProfileWrite.SESSION_CHANGED, write)
        assertEquals(UserInfo(id = "other-account", name = "Other"), repository.getCurrentUser())
        assertNull(storage.retrieveFetchedProfile())
    }

    @Test
    fun `an attempt is recorded for the current session only`() = runTest {
        val repository = oidcRepository(storage)
        signIn()
        val session = assertNotNull(repository.captureProfileFetchSession())

        assertTrue(repository.recordProfileFetchAttempt(session, 5_000L))
        assertEquals(5_000L, repository.captureProfileFetchSession()?.attemptedAt)

        repository.clearLocalSession()
        assertFalse(repository.recordProfileFetchAttempt(session, 6_000L))
        assertNull(storage.retrieveProfileFetchAttemptedAt())
    }

    /** Stores a committed session for [user], as a completed sign-in does. */
    private suspend fun signIn(user: UserInfo = sessionUser) {
        val generation = storage.retrieveSessionGeneration() + 1
        storage.storeNewSessionTokens(
            TokenResponse(
                accessToken = "access-token-$generation",
                refreshToken = "refresh-token",
                idToken = testIdToken("login-id-token", subject = user.id),
                expiresIn = 3600,
                tokenType = "Bearer"
            ),
            user
        )
        storage.storeSessionGeneration(generation)
        storage.storeCommittedTokenGeneration(generation)
    }
}
