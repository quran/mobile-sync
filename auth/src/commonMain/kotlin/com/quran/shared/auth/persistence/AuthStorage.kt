package com.quran.shared.auth.persistence

import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.utils.currentTimeMillis
import com.quran.shared.di.AppScope
import com.russhwolf.settings.coroutines.SuspendSettings
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.native.HiddenFromObjC
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.publicvalue.multiplatform.oidc.tokenstore.TokenStore

/**
 * Persists authentication token material and non-secret session metadata across app restarts.
 *
 * OAuth tokens are stored through [TokenStore] so each platform can use its secure storage
 * implementation. Metadata such as expiry, scope, and cached profile data remains outside the
 * token store because the OIDC token-store API intentionally owns only token material.
 */
@SingleIn(AppScope::class)
@HiddenFromObjC
class AuthStorage @Inject constructor(
    private val tokenStore: TokenStore,
    private val settings: SuspendSettings,
    private val json: Json
) {

    suspend fun retrieveStoredAccessToken(): String? = tokenStore.getAccessToken()
    suspend fun retrieveStoredRefreshToken(): String? = tokenStore.getRefreshToken()
    suspend fun retrieveStoredIdToken(): String? = tokenStore.getIdToken()
    suspend fun retrieveStoredScope(): String? = settings.getStringOrNull(KEY_SCOPE)
    suspend fun retrieveTokenExpiration(): Long = settings.getLong(KEY_TOKEN_EXPIRATION, 0)
    suspend fun retrieveSessionGeneration(): Long = settings.getLong(KEY_SESSION_GENERATION, 0)
    suspend fun retrieveCommittedTokenGeneration(): Long? =
        settings.getLongOrNull(KEY_COMMITTED_TOKEN_GENERATION)
    suspend fun retrieveTokenWriteGeneration(): Long =
        settings.getLong(KEY_TOKEN_WRITE_GENERATION, 0)
    suspend fun retrieveCommittedTokenWriteGeneration(): Long? =
        settings.getLongOrNull(KEY_COMMITTED_TOKEN_WRITE_GENERATION)

    suspend fun storeSessionGeneration(generation: Long) {
        settings.putLong(KEY_SESSION_GENERATION, generation)
    }

    suspend fun storeCommittedTokenGeneration(generation: Long) {
        settings.putLong(KEY_COMMITTED_TOKEN_GENERATION, generation)
        settings.putLong(KEY_COMMITTED_TOKEN_WRITE_GENERATION, retrieveTokenWriteGeneration())
    }

    suspend fun clearTokenCommitMetadata() {
        var failure: Exception? = null

        suspend fun attempt(block: suspend () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                if (failure == null) {
                    failure = e
                }
            }
        }

        attempt { settings.remove(KEY_COMMITTED_TOKEN_GENERATION) }
        attempt { settings.remove(KEY_COMMITTED_TOKEN_WRITE_GENERATION) }

        failure?.let { throw it }
    }

    /**
     * Retrieves cached user profile data from non-token session metadata.
     */
    suspend fun retrieveUserInfo(): UserInfo? {
        val userInfoJson = settings.getStringOrNull(KEY_USER_INFO) ?: return null
        return try {
            json.decodeFromString<UserInfo>(userInfoJson)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Stores token response data for an existing session while preserving stable refresh and ID
     * tokens when a refresh response omits them.
     *
     * Some providers return only a new access token during refresh. Passing null directly to
     * [TokenStore.saveTokens] would remove the previous refresh token, so this method reads the
     * existing secure token values first and keeps them unless replacements are present.
     *
     * @param tokenResponse token response returned from a refresh or same-session update.
     */
    suspend fun storeTokens(tokenResponse: TokenResponse) {
        storeTokens(tokenResponse, preserveExistingTokens = true)
    }

    /**
     * Stores token response data for a newly completed interactive login session.
     *
     * This intentionally does not preserve existing refresh or ID tokens. A successful login or
     * login-continuation is a session boundary, so stale identity metadata must be removed before
     * the new token material is stored.
     *
     * @param tokenResponse token response returned from a successful interactive login.
     */
    suspend fun storeNewSessionTokens(tokenResponse: TokenResponse) {
        val previousMetadata = snapshotSessionMetadata()
        try {
            clearSessionMetadata()
            storeTokens(tokenResponse, preserveExistingTokens = false)
        } catch (e: Exception) {
            try {
                withContext(NonCancellable) {
                    restoreSessionMetadata(previousMetadata)
                }
            } catch (_: Exception) {
                // Token rollback already left the advanced write marker when full restore was unsafe.
            }
            throw e
        }
    }

    private suspend fun storeTokens(
        tokenResponse: TokenResponse,
        preserveExistingTokens: Boolean
    ) {
        val previousAccessToken = tokenStore.getAccessToken()
        val previousRefreshToken = tokenStore.getRefreshToken()
        val previousIdToken = tokenStore.getIdToken()
        val previousScope = settings.getStringOrNull(KEY_SCOPE)
        val previousExpiration = settings.getLongOrNull(KEY_TOKEN_EXPIRATION)
        val previousRetrievedAt = settings.getLongOrNull(KEY_TOKEN_RETRIEVED_AT)
        val previousTokenWriteGeneration = settings.getLongOrNull(KEY_TOKEN_WRITE_GENERATION)
        try {
            markTokenWriteStarted(previousTokenWriteGeneration)
            val expirationTime = if (tokenResponse.expiresAt != null) {
                try {
                    Instant.parse(tokenResponse.expiresAt).toEpochMilliseconds()
                } catch (e: Exception) {
                    currentTimeMillis() + (tokenResponse.expiresIn * 1000)
                }
            } else {
                currentTimeMillis() + (tokenResponse.expiresIn * 1000)
            }
            val refreshToken = tokenResponse.refreshToken
                ?: if (preserveExistingTokens) tokenStore.getRefreshToken() else null
            val idToken = tokenResponse.idToken
                ?: if (preserveExistingTokens) tokenStore.getIdToken() else null

            tokenStore.saveTokens(
                accessToken = tokenResponse.accessToken,
                refreshToken = refreshToken,
                idToken = idToken
            )
            tokenResponse.scope?.let { settings.putString(KEY_SCOPE, it) }
            settings.putLong(KEY_TOKEN_EXPIRATION, expirationTime)
            settings.putLong(KEY_TOKEN_RETRIEVED_AT, currentTimeMillis())
        } catch (e: Exception) {
            rollbackTokenWrite(
                accessToken = previousAccessToken,
                refreshToken = previousRefreshToken,
                idToken = previousIdToken,
                scope = previousScope,
                expiration = previousExpiration,
                retrievedAt = previousRetrievedAt,
                tokenWriteGeneration = previousTokenWriteGeneration
            )
            throw e
        }
    }

    suspend fun storeUserInfo(userInfo: UserInfo) {
        settings.putString(KEY_USER_INFO, json.encodeToString(userInfo))
    }

    /**
     * Clears secure token material and non-token auth metadata.
     */
    suspend fun clearAllTokens() {
        var failure: Exception? = null

        suspend fun attempt(block: suspend () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                if (failure == null) {
                    failure = e
                }
            }
        }

        attempt { clearTokenCommitMetadata() }
        attempt { tokenStore.removeAccessToken() }
        attempt { tokenStore.removeRefreshToken() }
        attempt { tokenStore.removeIdToken() }
        attempt { clearSessionMetadata() }

        failure?.let { throw it }
    }

    private suspend fun markTokenWriteStarted(previousTokenWriteGeneration: Long?) {
        val writeGenerationBase = maxOf(
            previousTokenWriteGeneration ?: 0L,
            retrieveCommittedTokenWriteGeneration() ?: 0L
        )
        settings.putLong(KEY_TOKEN_WRITE_GENERATION, writeGenerationBase + 1)
    }

    private suspend fun restoreTokens(
        accessToken: String?,
        refreshToken: String?,
        idToken: String?
    ) {
        if (accessToken == null) {
            tokenStore.removeAccessToken()
            tokenStore.removeRefreshToken()
            tokenStore.removeIdToken()
        } else {
            tokenStore.saveTokens(
                accessToken = accessToken,
                refreshToken = refreshToken,
                idToken = idToken
            )
        }
    }

    private suspend fun restoreTokenMetadata(
        scope: String?,
        expiration: Long?,
        retrievedAt: Long?
    ) {
        if (scope == null) {
            settings.remove(KEY_SCOPE)
        } else {
            settings.putString(KEY_SCOPE, scope)
        }
        if (expiration == null) {
            settings.remove(KEY_TOKEN_EXPIRATION)
        } else {
            settings.putLong(KEY_TOKEN_EXPIRATION, expiration)
        }
        if (retrievedAt == null) {
            settings.remove(KEY_TOKEN_RETRIEVED_AT)
        } else {
            settings.putLong(KEY_TOKEN_RETRIEVED_AT, retrievedAt)
        }
    }

    private suspend fun rollbackTokenWrite(
        accessToken: String?,
        refreshToken: String?,
        idToken: String?,
        scope: String?,
        expiration: Long?,
        retrievedAt: Long?,
        tokenWriteGeneration: Long?
    ) {
        try {
            withContext(NonCancellable) {
                restoreTokens(accessToken, refreshToken, idToken)
                restoreTokenMetadata(
                    scope = scope,
                    expiration = expiration,
                    retrievedAt = retrievedAt
                )
                if (tokenWriteGeneration == null) {
                    settings.remove(KEY_TOKEN_WRITE_GENERATION)
                } else {
                    settings.putLong(KEY_TOKEN_WRITE_GENERATION, tokenWriteGeneration)
                }
            }
        } catch (_: Exception) {
            // Leave the advanced write marker so partially written token material stays hidden.
        }
    }

    private suspend fun snapshotSessionMetadata(): SessionMetadata =
        SessionMetadata(
            scope = settings.getStringOrNull(KEY_SCOPE),
            expiration = settings.getLongOrNull(KEY_TOKEN_EXPIRATION),
            retrievedAt = settings.getLongOrNull(KEY_TOKEN_RETRIEVED_AT),
            userInfo = settings.getStringOrNull(KEY_USER_INFO)
        )

    private suspend fun restoreSessionMetadata(metadata: SessionMetadata) {
        restoreTokenMetadata(
            scope = metadata.scope,
            expiration = metadata.expiration,
            retrievedAt = metadata.retrievedAt
        )
        if (metadata.userInfo == null) {
            settings.remove(KEY_USER_INFO)
        } else {
            settings.putString(KEY_USER_INFO, metadata.userInfo)
        }
    }

    private suspend fun clearSessionMetadata() {
        settings.remove(KEY_SCOPE)
        settings.remove(KEY_USER_INFO)
        settings.remove(KEY_TOKEN_EXPIRATION)
        settings.remove(KEY_TOKEN_RETRIEVED_AT)
    }

    companion object {
        private const val KEY_SCOPE = "scope"
        private const val KEY_TOKEN_EXPIRATION = "token_expiration"
        private const val KEY_TOKEN_RETRIEVED_AT = "token_retrieved_at"
        private const val KEY_USER_INFO = "user_info"
        private const val KEY_SESSION_GENERATION = "session_generation"
        private const val KEY_COMMITTED_TOKEN_GENERATION = "committed_token_generation"
        private const val KEY_TOKEN_WRITE_GENERATION = "token_write_generation"
        private const val KEY_COMMITTED_TOKEN_WRITE_GENERATION = "committed_token_write_generation"
    }
}

private data class SessionMetadata(
    val scope: String?,
    val expiration: Long?,
    val retrievedAt: Long?,
    val userInfo: String?
)
