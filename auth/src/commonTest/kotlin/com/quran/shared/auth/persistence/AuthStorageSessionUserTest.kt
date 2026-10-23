package com.quran.shared.auth.persistence

import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.repository.RecordingTokenStore
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.SuspendSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The session's user is written with the tokens, in the same rollback-protected write. */
class AuthStorageSessionUserTest {
    private val tokenStore = RecordingTokenStore(
        accessToken = "old-access-token",
        refreshToken = "old-refresh-token",
        idToken = "old-id-token"
    )
    private val settings = MapSettings().toSuspendSettings()
    private val storage = AuthStorage(tokenStore, settings, Json { ignoreUnknownKeys = true })
    private val oldUser = UserInfo(id = "user-id", name = "Old Name")

    @Test
    fun `a refresh with a user stores it with the tokens`() = runTest {
        storage.storeUserInfo(oldUser)

        storage.storeTokens(refreshResponse(), UserInfo(id = "user-id", name = "New Name"))

        assertEquals("new-access-token", tokenStore.getAccessToken())
        assertEquals(UserInfo(id = "user-id", name = "New Name"), storage.retrieveUserInfo())
    }

    @Test
    fun `a refresh without a user keeps the stored one`() = runTest {
        storage.storeUserInfo(oldUser)

        storage.storeTokens(refreshResponse())

        assertEquals("new-access-token", tokenStore.getAccessToken())
        assertEquals(oldUser, storage.retrieveUserInfo())
    }

    @Test
    fun `a user that fails to store rolls back the token write`() = runTest {
        storage.storeUserInfo(oldUser)
        val failingStorage = AuthStorage(
            tokenStore,
            FailingUserWriteSettings(settings),
            Json { ignoreUnknownKeys = true }
        )

        assertFailsWith<IllegalStateException> {
            failingStorage.storeTokens(refreshResponse(), UserInfo(id = "user-id", name = "New Name"))
        }

        assertEquals("old-access-token", tokenStore.getAccessToken())
        assertEquals("old-refresh-token", tokenStore.getRefreshToken())
        assertEquals(oldUser, storage.retrieveUserInfo())
    }

    @Test
    fun `a new session whose user fails to store restores the previous session`() = runTest {
        storage.storeUserInfo(oldUser)
        val failingStorage = AuthStorage(
            tokenStore,
            FailingUserWriteSettings(settings),
            Json { ignoreUnknownKeys = true }
        )

        assertFailsWith<IllegalStateException> {
            failingStorage.storeNewSessionTokens(refreshResponse(), UserInfo(id = "other-user"))
        }

        assertEquals("old-access-token", tokenStore.getAccessToken())
        assertEquals(oldUser, storage.retrieveUserInfo())
    }

    private fun refreshResponse() = TokenResponse(
        accessToken = "new-access-token",
        expiresIn = 3600,
        tokenType = "Bearer"
    )

    /** Settings whose next user write fails, after which writes go through again so the rollback can run. */
    private class FailingUserWriteSettings(
        private val delegate: SuspendSettings
    ) : SuspendSettings by delegate {
        private var hasFailed = false

        override suspend fun putString(key: String, value: String) {
            if (key == "user_info" && !hasFailed) {
                hasFailed = true
                throw IllegalStateException("user write failed")
            }
            delegate.putString(key, value)
        }
    }
}
