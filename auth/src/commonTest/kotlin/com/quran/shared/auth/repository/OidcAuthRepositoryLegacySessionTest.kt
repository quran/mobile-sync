package com.quran.shared.auth.repository

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import com.quran.shared.auth.TEST_SUBJECT
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.auth.testIdToken
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sessions an older version stored: their user came from `/userinfo`, or is missing because that fetch failed. Their
 * account must not change, or sync would take it for another account and wipe the local data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OidcAuthRepositoryLegacySessionTest {
    private val storage = AuthStorage(
        tokenStore = RecordingTokenStore(
            accessToken = "old-access-token",
            refreshToken = "old-refresh-token",
            idToken = testIdToken(claims = mapOf("name" to "Token Name", "email" to "token@example.com"))
        ),
        settings = MapSettings().toSuspendSettings(),
        json = Json { ignoreUnknownKeys = true }
    )
    private val userinfoUser = UserInfo(id = TEST_SUBJECT, name = "Userinfo Name", email = "userinfo@example.com")

    @Test
    fun `a user stored from userinfo is kept as it is`() = runTest(UnconfinedTestDispatcher()) {
        storage.storeUserInfo(userinfoUser)
        commitLegacySession()

        assertEquals(userinfoUser, oidcRepository(storage).getCurrentUser())
        assertEquals(userinfoUser, storage.retrieveUserInfo())
    }

    @Test
    fun `a session stored without a user reads it from the stored ID token once and keeps it`() =
        runTest(UnconfinedTestDispatcher()) {
            commitLegacySession()
            val tokenUser = UserInfo(id = TEST_SUBJECT, name = "Token Name", email = "token@example.com")

            assertEquals(tokenUser, oidcRepository(storage).getCurrentUser())
            assertEquals(tokenUser, storage.retrieveUserInfo(), "the user is persisted")
        }

    @Test
    fun `a session stored without a user and with an unreadable ID token has no account`() =
        runTest(UnconfinedTestDispatcher()) {
            val storage = legacyStorageWithIdToken("opaque-id-token")

            val repository = oidcRepository(storage)

            assertTrue(repository.isLoggedIn())
            assertNull(repository.getCurrentUser())
            assertNull(storage.retrieveUserInfo())
        }

    /** Hosts forward warnings to crash reporting, and auth headers and the user are read on every request. */
    @Test
    fun `an unreadable stored ID token is decoded only when needed and logged once per session`() =
        runTest(UnconfinedTestDispatcher()) {
            val storage = legacyStorageWithIdToken("opaque-id-token", tokenExpiration = Long.MAX_VALUE)
            val warnings = mutableListOf<String>()
            val logger = Logger(
                StaticConfig(
                    minSeverity = Severity.Warn,
                    logWriterList = listOf(
                        object : LogWriter() {
                            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                                warnings += message
                            }
                        }
                    )
                ),
                "OidcAuthRepository"
            )
            val repository = oidcRepository(storage, logger = logger)

            repeat(3) { assertEquals("old-access-token", repository.getAuthHeaders()["x-auth-token"]) }
            assertEquals(emptyList(), warnings, "a valid access token needs no account")

            repeat(3) { assertNull(repository.getCurrentUser()) }
            assertEquals(1, warnings.size, "$warnings")
            assertTrue("can't name the account" in warnings.single(), warnings.single())
        }

    @Test
    fun `a refresh for the account of a user stored from userinfo keeps the session`() =
        runTest(UnconfinedTestDispatcher()) {
            storage.storeUserInfo(userinfoUser)
            commitLegacySession()
            val refreshedIdToken = testIdToken(claims = mapOf("name" to "New Name"))
            val repository = oidcRepository(storage, refreshingClient(refreshedIdToken))

            assertTrue(repository.forceRefreshTokens())

            assertEquals(UserInfo(id = TEST_SUBJECT, name = "New Name"), repository.getCurrentUser())
        }

    /** Before this version, the next refresh's `/userinfo` fetch filled in the missing user; its ID token does now. */
    @Test
    fun `a refresh gives a session with no known account the user of its ID token`() =
        runTest(UnconfinedTestDispatcher()) {
            val storage = legacyStorageWithIdToken("opaque-id-token")
            val refreshedIdToken = testIdToken(claims = mapOf("name" to "New Name"))
            val repository = oidcRepository(storage, refreshingClient(refreshedIdToken))

            assertTrue(repository.forceRefreshTokens())

            assertEquals(UserInfo(id = TEST_SUBJECT, name = "New Name"), repository.getCurrentUser())
        }

    private suspend fun commitLegacySession() {
        storage.storeSessionGeneration(1)
        storage.storeCommittedTokenGeneration(1)
    }

    private suspend fun legacyStorageWithIdToken(idToken: String, tokenExpiration: Long? = null): AuthStorage {
        val settings = MapSettings().toSuspendSettings()
        tokenExpiration?.let { settings.putLong("token_expiration", it) }
        return AuthStorage(
            tokenStore = RecordingTokenStore(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = idToken
            ),
            settings = settings,
            json = Json { ignoreUnknownKeys = true }
        ).apply {
            storeSessionGeneration(1)
            storeCommittedTokenGeneration(1)
        }
    }

    private fun refreshingClient(idToken: String) = BlockingRefreshOidcClient(
        refreshResponse = AccessTokenResponse(
            access_token = "fresh-access-token",
            token_type = "Bearer",
            expires_in = 3600,
            id_token = idToken
        )
    ).also { it.refreshCanFinish.complete(Unit) }
}
