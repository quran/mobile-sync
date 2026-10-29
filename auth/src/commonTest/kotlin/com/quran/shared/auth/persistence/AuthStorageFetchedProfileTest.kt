package com.quran.shared.auth.persistence

import com.quran.shared.auth.model.FetchedProfile
import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.repository.RecordingTokenStore
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The fetched profile is the session's: token refreshes keep it, and a logout or a new session removes it. */
class AuthStorageFetchedProfileTest {
    private val settings = MapSettings().toSuspendSettings()
    private val storage = AuthStorage(
        RecordingTokenStore(accessToken = "access-token", refreshToken = "refresh-token", idToken = "id-token"),
        settings,
        Json { ignoreUnknownKeys = true }
    )
    private val profile = FetchedProfile(
        id = "user-id",
        name = "New Name",
        firstName = "New",
        lastName = "Name",
        email = "user@example.com",
        photoUrl = "https://example.com/photo.png",
        fetchedAt = 1_000L
    )

    @Test
    fun `a stored profile and attempt read back`() = runTest {
        storage.storeFetchedProfile(profile)
        storage.storeProfileFetchAttemptedAt(2_000L)

        assertEquals(profile, storage.retrieveFetchedProfile())
        assertEquals(2_000L, storage.retrieveProfileFetchAttemptedAt())
    }

    @Test
    fun `a token refresh keeps the profile`() = runTest {
        storage.storeFetchedProfile(profile)
        storage.storeProfileFetchAttemptedAt(2_000L)

        storage.storeTokens(tokens("refreshed-access-token"), UserInfo(id = "user-id", name = "Old Name"))

        assertEquals(profile, storage.retrieveFetchedProfile())
        assertEquals(2_000L, storage.retrieveProfileFetchAttemptedAt())
    }

    @Test
    fun `a new session removes the previous session's profile`() = runTest {
        storage.storeFetchedProfile(profile)
        storage.storeProfileFetchAttemptedAt(2_000L)

        storage.storeNewSessionTokens(tokens("new-session-token"), UserInfo(id = "other-user"))

        assertNull(storage.retrieveFetchedProfile())
        assertNull(storage.retrieveProfileFetchAttemptedAt())
    }

    @Test
    fun `a logout removes the profile`() = runTest {
        storage.storeFetchedProfile(profile)
        storage.storeProfileFetchAttemptedAt(2_000L)

        storage.clearAllTokens()

        assertNull(storage.retrieveFetchedProfile())
        assertNull(storage.retrieveProfileFetchAttemptedAt())
    }

    @Test
    fun `an unreadable profile reads as none`() = runTest {
        settings.putString("fetched_profile", "{not json")

        assertNull(storage.retrieveFetchedProfile())
    }

    private fun tokens(accessToken: String) = TokenResponse(
        accessToken = accessToken,
        refreshToken = null,
        idToken = null,
        expiresIn = 3600,
        tokenType = "Bearer"
    )
}
