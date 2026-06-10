package com.quran.shared.auth.persistence

import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.model.UserInfo
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.SuspendSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.tokenstore.TokenStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthStorageTest {

    @Test
    fun `storeTokens persists OAuth tokens through TokenStore`() = runTest {
        val tokenStore = RecordingTokenStore()
        val storage = authStorage(tokenStore)

        storage.storeTokens(
            TokenResponse(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                idToken = "id-token",
                expiresIn = 3600,
                tokenType = "Bearer",
                scope = "openid profile"
            )
        )

        assertEquals("access-token", tokenStore.accessToken)
        assertEquals("refresh-token", tokenStore.refreshToken)
        assertEquals("id-token", tokenStore.idToken)
        assertEquals("openid profile", storage.retrieveStoredScope())
        assertEquals("access-token", storage.retrieveStoredAccessToken())
        assertEquals("refresh-token", storage.retrieveStoredRefreshToken())
        assertEquals("id-token", storage.retrieveStoredIdToken())
    }

    @Test
    fun `storeTokens preserves refresh token when refresh response omits one`() = runTest {
        val tokenStore = RecordingTokenStore(
            accessToken = "old-access-token",
            refreshToken = "stable-refresh-token",
            idToken = "old-id-token"
        )
        val storage = authStorage(tokenStore)

        storage.storeTokens(
            TokenResponse(
                accessToken = "new-access-token",
                refreshToken = null,
                idToken = null,
                expiresIn = 3600,
                tokenType = "Bearer"
            )
        )

        assertEquals("new-access-token", tokenStore.accessToken)
        assertEquals("stable-refresh-token", tokenStore.refreshToken)
        assertEquals("old-id-token", tokenStore.idToken)
    }

    @Test
    fun `storeNewSessionTokens clears stale identity metadata and does not preserve old tokens`() = runTest {
        val tokenStore = RecordingTokenStore(
            accessToken = "old-access-token",
            refreshToken = "old-refresh-token",
            idToken = "old-id-token"
        )
        val storage = authStorage(tokenStore)
        storage.storeUserInfo(UserInfo(id = "old-user", name = "Old User"))

        storage.storeNewSessionTokens(
            TokenResponse(
                accessToken = "new-access-token",
                refreshToken = null,
                idToken = null,
                expiresIn = 3600,
                tokenType = "Bearer"
            )
        )

        assertEquals("new-access-token", tokenStore.accessToken)
        assertNull(tokenStore.refreshToken)
        assertNull(tokenStore.idToken)
        assertNull(storage.retrieveUserInfo())
    }

    @Test
    fun `clearAllTokens clears token store and auth metadata`() = runTest {
        val tokenStore = RecordingTokenStore(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            idToken = "id-token"
        )
        val storage = authStorage(tokenStore)

        storage.storeTokens(
            TokenResponse(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                idToken = "id-token",
                expiresIn = 3600,
                tokenType = "Bearer",
                scope = "openid profile"
            )
        )
        storage.storeSessionGeneration(1)
        storage.storeCommittedTokenGeneration(1)

        storage.clearAllTokens()

        assertNull(tokenStore.accessToken)
        assertNull(tokenStore.refreshToken)
        assertNull(tokenStore.idToken)
        assertNull(storage.retrieveStoredScope())
        assertEquals(0, storage.retrieveTokenExpiration())
        assertNull(storage.retrieveCommittedTokenGeneration())
    }

    @Test
    fun `storeTokens cancellation restores previous token metadata`() = runTest {
        val tokenStore = RecordingTokenStore(
            accessToken = "old-access-token",
            refreshToken = "old-refresh-token",
            idToken = "old-id-token"
        )
        val baseSettings = MapSettings().toSuspendSettings()
        val storage = authStorage(tokenStore, baseSettings)
        storage.storeTokens(
            TokenResponse(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token",
                expiresIn = 120,
                tokenType = "Bearer",
                scope = "old-scope"
            )
        )
        val oldExpiration = storage.retrieveTokenExpiration()
        val cancellingStorage = authStorage(
            tokenStore = tokenStore,
            settings = OneShotCancellingPutLongSettings(
                delegate = baseSettings,
                cancelKey = "token_retrieved_at"
            )
        )

        kotlin.test.assertFailsWith<CancellationException> {
            cancellingStorage.storeTokens(
                TokenResponse(
                    accessToken = "new-access-token",
                    refreshToken = "new-refresh-token",
                    idToken = "new-id-token",
                    expiresIn = 7200,
                    tokenType = "Bearer",
                    scope = "new-scope"
                )
            )
        }

        assertEquals("old-access-token", tokenStore.accessToken)
        assertEquals("old-refresh-token", tokenStore.refreshToken)
        assertEquals("old-id-token", tokenStore.idToken)
        assertEquals("old-scope", storage.retrieveStoredScope())
        assertEquals(oldExpiration, storage.retrieveTokenExpiration())
    }

    @Test
    fun `storeTokens ordinary failure restores previous token metadata and write marker`() = runTest {
        val tokenStore = RecordingTokenStore(
            accessToken = "old-access-token",
            refreshToken = "old-refresh-token",
            idToken = "old-id-token"
        )
        val baseSettings = MapSettings().toSuspendSettings()
        val storage = authStorage(tokenStore, baseSettings)
        storage.storeTokens(
            TokenResponse(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token",
                expiresIn = 120,
                tokenType = "Bearer",
                scope = "old-scope"
            )
        )
        storage.storeSessionGeneration(1)
        storage.storeCommittedTokenGeneration(1)
        val oldExpiration = storage.retrieveTokenExpiration()
        val oldWriteGeneration = storage.retrieveTokenWriteGeneration()
        val failingStorage = authStorage(
            tokenStore = tokenStore,
            settings = OneShotFailingPutLongSettings(
                delegate = baseSettings,
                failKey = "token_retrieved_at"
            )
        )

        kotlin.test.assertFailsWith<IllegalStateException> {
            failingStorage.storeTokens(
                TokenResponse(
                    accessToken = "new-access-token",
                    refreshToken = "new-refresh-token",
                    idToken = "new-id-token",
                    expiresIn = 7200,
                    tokenType = "Bearer",
                    scope = "new-scope"
                )
            )
        }

        assertEquals("old-access-token", tokenStore.accessToken)
        assertEquals("old-refresh-token", tokenStore.refreshToken)
        assertEquals("old-id-token", tokenStore.idToken)
        assertEquals("old-scope", storage.retrieveStoredScope())
        assertEquals(oldExpiration, storage.retrieveTokenExpiration())
        assertEquals(oldWriteGeneration, storage.retrieveTokenWriteGeneration())
    }

    @Test
    fun `storeTokens cancellation while starting token write restores absent write marker`() = runTest {
        val tokenStore = RecordingTokenStore(
            accessToken = "old-access-token",
            refreshToken = "old-refresh-token",
            idToken = "old-id-token"
        )
        val baseSettings = MapSettings().toSuspendSettings()
        val storage = authStorage(tokenStore, baseSettings)
        storage.storeTokens(
            TokenResponse(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token",
                expiresIn = 120,
                tokenType = "Bearer"
            )
        )
        storage.storeSessionGeneration(1)
        storage.storeCommittedTokenGeneration(1)
        baseSettings.remove("token_write_generation")
        val cancellingStorage = authStorage(
            tokenStore = tokenStore,
            settings = OneShotCancellingPutLongSettings(
                delegate = baseSettings,
                cancelKey = "token_write_generation"
            )
        )

        kotlin.test.assertFailsWith<CancellationException> {
            cancellingStorage.storeTokens(
                TokenResponse(
                    accessToken = "new-access-token",
                    refreshToken = "new-refresh-token",
                    idToken = "new-id-token",
                    expiresIn = 7200,
                    tokenType = "Bearer"
                )
            )
        }

        assertNull(baseSettings.getLongOrNull("token_write_generation"))
        assertEquals("old-access-token", tokenStore.accessToken)
        assertEquals("old-refresh-token", tokenStore.refreshToken)
        assertEquals("old-id-token", tokenStore.idToken)
    }

    @Test
    fun `storeTokens cancellation while starting token write restores lower previous write marker`() = runTest {
        val tokenStore = RecordingTokenStore(
            accessToken = "old-access-token",
            refreshToken = "old-refresh-token",
            idToken = "old-id-token"
        )
        val baseSettings = MapSettings().toSuspendSettings()
        val storage = authStorage(tokenStore, baseSettings)
        storage.storeTokens(
            TokenResponse(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token",
                expiresIn = 120,
                tokenType = "Bearer"
            )
        )
        baseSettings.putLong("token_write_generation", 3)
        baseSettings.putLong("committed_token_write_generation", 5)
        val cancellingStorage = authStorage(
            tokenStore = tokenStore,
            settings = OneShotCancellingPutLongSettings(
                delegate = baseSettings,
                cancelKey = "token_write_generation"
            )
        )

        kotlin.test.assertFailsWith<CancellationException> {
            cancellingStorage.storeTokens(
                TokenResponse(
                    accessToken = "new-access-token",
                    refreshToken = "new-refresh-token",
                    idToken = "new-id-token",
                    expiresIn = 7200,
                    tokenType = "Bearer"
                )
            )
        }

        assertEquals(3, baseSettings.getLongOrNull("token_write_generation"))
        assertEquals("old-access-token", tokenStore.accessToken)
        assertEquals("old-refresh-token", tokenStore.refreshToken)
        assertEquals("old-id-token", tokenStore.idToken)
    }

    @Test
    fun `storeNewSessionTokens cancellation restores previous session metadata`() = runTest {
        val tokenStore = RecordingTokenStore(
            accessToken = "old-access-token",
            refreshToken = "old-refresh-token",
            idToken = "old-id-token"
        )
        val baseSettings = MapSettings().toSuspendSettings()
        val storage = authStorage(tokenStore, baseSettings)
        storage.storeTokens(
            TokenResponse(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token",
                expiresIn = 120,
                tokenType = "Bearer",
                scope = "old-scope"
            )
        )
        storage.storeUserInfo(UserInfo(id = "old-user", name = "Old User"))
        val oldExpiration = storage.retrieveTokenExpiration()
        val cancellingStorage = authStorage(
            tokenStore = tokenStore,
            settings = OneShotCancellingPutLongSettings(
                delegate = baseSettings,
                cancelKey = "token_retrieved_at"
            )
        )

        kotlin.test.assertFailsWith<CancellationException> {
            cancellingStorage.storeNewSessionTokens(
                TokenResponse(
                    accessToken = "new-access-token",
                    refreshToken = "new-refresh-token",
                    idToken = "new-id-token",
                    expiresIn = 7200,
                    tokenType = "Bearer",
                    scope = "new-scope"
                )
            )
        }

        assertEquals("old-access-token", tokenStore.accessToken)
        assertEquals("old-refresh-token", tokenStore.refreshToken)
        assertEquals("old-id-token", tokenStore.idToken)
        assertEquals("old-scope", storage.retrieveStoredScope())
        assertEquals(oldExpiration, storage.retrieveTokenExpiration())
        assertEquals("old-user", storage.retrieveUserInfo()?.id)
    }

    @Test
    fun `clearAllTokens preserves token write generation monotonicity`() = runTest {
        val tokenStore = RecordingTokenStore()
        val storage = authStorage(tokenStore)
        storage.storeTokens(
            TokenResponse(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                idToken = "id-token",
                expiresIn = 3600,
                tokenType = "Bearer"
            )
        )
        val writeGeneration = storage.retrieveTokenWriteGeneration()

        storage.clearAllTokens()

        assertEquals(writeGeneration, storage.retrieveTokenWriteGeneration())
    }

    @Test
    fun `clearAllTokens attempts all commit marker removals after first marker failure`() = runTest {
        val tokenStore = RecordingTokenStore(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            idToken = "id-token",
            failRemoveAccessToken = true
        )
        val baseSettings = MapSettings().toSuspendSettings()
        val storage = authStorage(tokenStore, baseSettings)
        storage.storeTokens(
            TokenResponse(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                idToken = "id-token",
                expiresIn = 3600,
                tokenType = "Bearer"
            )
        )
        storage.storeSessionGeneration(1)
        storage.storeCommittedTokenGeneration(1)
        val clearingStorage = authStorage(
            tokenStore = tokenStore,
            settings = FailingRemoveSettings(
                delegate = baseSettings,
                failRemoveKeys = setOf("committed_token_generation")
            )
        )

        val failure = kotlin.test.assertFailsWith<IllegalStateException> {
            clearingStorage.clearAllTokens()
        }

        assertEquals("failed to remove committed_token_generation", failure.message)
        assertEquals("access-token", tokenStore.accessToken)
        assertEquals(1, storage.retrieveCommittedTokenGeneration())
        assertNull(storage.retrieveCommittedTokenWriteGeneration())
    }

    @Test
    fun `storeTokens advances past stale committed token write generation`() = runTest {
        val tokenStore = RecordingTokenStore()
        val settings = MapSettings().toSuspendSettings()
        val storage = authStorage(tokenStore, settings)
        storage.storeTokens(
            TokenResponse(
                accessToken = "old-access-token",
                refreshToken = "old-refresh-token",
                idToken = "old-id-token",
                expiresIn = 3600,
                tokenType = "Bearer"
            )
        )
        storage.storeSessionGeneration(1)
        storage.storeCommittedTokenGeneration(1)
        settings.remove("token_write_generation")

        storage.storeTokens(
            TokenResponse(
                accessToken = "new-access-token",
                refreshToken = "new-refresh-token",
                idToken = "new-id-token",
                expiresIn = 3600,
                tokenType = "Bearer"
            )
        )

        assertEquals(2, storage.retrieveTokenWriteGeneration())
    }

    private fun authStorage(tokenStore: RecordingTokenStore): AuthStorage {
        return authStorage(
            tokenStore = tokenStore,
            settings = MapSettings().toSuspendSettings()
        )
    }

    private fun authStorage(
        tokenStore: RecordingTokenStore,
        settings: SuspendSettings
    ): AuthStorage {
        return AuthStorage(
            tokenStore = tokenStore,
            settings = settings,
            json = Json { ignoreUnknownKeys = true }
        )
    }
}

private class OneShotCancellingPutLongSettings(
    private val delegate: SuspendSettings,
    private val cancelKey: String
) : SuspendSettings {
    private var hasCanceled = false

    override suspend fun keys(): Set<String> = delegate.keys()
    override suspend fun size(): Int = delegate.size()
    override suspend fun clear() = delegate.clear()
    override suspend fun remove(key: String) = delegate.remove(key)
    override suspend fun hasKey(key: String): Boolean = delegate.hasKey(key)
    override suspend fun putInt(key: String, value: Int) = delegate.putInt(key, value)
    override suspend fun getInt(key: String, defaultValue: Int): Int = delegate.getInt(key, defaultValue)
    override suspend fun getIntOrNull(key: String): Int? = delegate.getIntOrNull(key)
    override suspend fun putLong(key: String, value: Long) {
        delegate.putLong(key, value)
        if (key == cancelKey && !hasCanceled) {
            hasCanceled = true
            throw CancellationException("metadata write canceled")
        }
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

private class OneShotFailingPutLongSettings(
    private val delegate: SuspendSettings,
    private val failKey: String
) : SuspendSettings {
    private var hasFailed = false

    override suspend fun keys(): Set<String> = delegate.keys()
    override suspend fun size(): Int = delegate.size()
    override suspend fun clear() = delegate.clear()
    override suspend fun remove(key: String) = delegate.remove(key)
    override suspend fun hasKey(key: String): Boolean = delegate.hasKey(key)
    override suspend fun putInt(key: String, value: Int) = delegate.putInt(key, value)
    override suspend fun getInt(key: String, defaultValue: Int): Int = delegate.getInt(key, defaultValue)
    override suspend fun getIntOrNull(key: String): Int? = delegate.getIntOrNull(key)
    override suspend fun putLong(key: String, value: Long) {
        delegate.putLong(key, value)
        if (key == failKey && !hasFailed) {
            hasFailed = true
            throw IllegalStateException("metadata write failed")
        }
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

private class FailingRemoveSettings(
    private val delegate: SuspendSettings,
    private val failRemoveKeys: Set<String>
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
    override suspend fun putLong(key: String, value: Long) = delegate.putLong(key, value)
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

private class RecordingTokenStore(
    accessToken: String? = null,
    refreshToken: String? = null,
    idToken: String? = null,
    private val failRemoveAccessToken: Boolean = false
) : TokenStore() {
    private val accessTokenState = MutableStateFlow(accessToken)
    private val refreshTokenState = MutableStateFlow(refreshToken)
    private val idTokenState = MutableStateFlow(idToken)

    var accessToken: String? = accessToken
        private set
    var refreshToken: String? = refreshToken
        private set
    var idToken: String? = idToken
        private set

    override val accessTokenFlow: StateFlow<String?> = accessTokenState
    override val refreshTokenFlow: StateFlow<String?> = refreshTokenState
    override val idTokenFlow: StateFlow<String?> = idTokenState

    override suspend fun getAccessToken(): String? = accessToken

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
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        this.idToken = idToken
        accessTokenState.value = accessToken
        refreshTokenState.value = refreshToken
        idTokenState.value = idToken
    }
}
