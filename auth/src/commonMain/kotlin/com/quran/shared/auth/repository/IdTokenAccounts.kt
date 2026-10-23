package com.quran.shared.auth.repository

import co.touchlab.kermit.Logger
import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.model.IdTokenUser
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.model.parseIdTokenUser
import com.quran.shared.auth.persistence.AuthStorage
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.CancellationException
import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse

/**
 * The ID-token side of [OidcAuthRepository]: reads the account a token response or the stored session names, and
 * revokes the refresh token of a response whose ID token can't be used. The repository's locks and generations decide
 * when each call runs and whether its result is used.
 */
internal class IdTokenAccounts(
    private val authConfig: AuthConfig,
    private val authStorage: AuthStorage,
    private val oidcClient: OpenIdConnectClient,
    private val configureTokenExchange: HttpRequestBuilder.() -> Unit,
    private val logger: Logger
) {
    /** The session generation whose unreadable stored ID token was last logged; used under the session lock. */
    private var loggedUnreadableIdTokenGeneration: Long? = null

    /** The user [response]'s ID token names, or null when the response has no ID token. */
    fun userOf(response: AccessTokenResponse): IdTokenUser? =
        response.id_token?.let { parseIdTokenUser(it, authConfig) }

    /**
     * The user the stored ID token names, for a session an older version stored without one. The caller holds the
     * session lock. The session's user is read on every call and hosts forward warnings to crash reporting, so a
     * rejected token is logged once per [sessionGeneration].
     */
    suspend fun storedIdTokenUser(sessionGeneration: Long): UserInfo? {
        val idToken = authStorage.retrieveStoredIdToken() ?: return null
        return when (val idTokenUser = parseIdTokenUser(idToken, authConfig)) {
            is IdTokenUser.Valid -> idTokenUser.userInfo
            is IdTokenUser.Rejected -> null.also {
                if (loggedUnreadableIdTokenGeneration != sessionGeneration) {
                    loggedUnreadableIdTokenGeneration = sessionGeneration
                    logger.w { "The stored ID token can't name the account: ${idTokenUser.reason}" }
                }
            }
        }
    }

    /**
     * Revokes the refresh token of a response discarded because its ID token can't be used, so its grant doesn't stay
     * valid on the server. Best effort: a failure is logged and never changes the caller's outcome.
     */
    suspend fun revokeUnusableGrant(response: AccessTokenResponse) {
        val refreshToken = response.refresh_token ?: return
        try {
            oidcClient.revokeToken(refreshToken, configureTokenExchange)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.cancellationCauseOrNull()?.let { throw it }
            logger.w(e) { "Failed to revoke the refresh token of a response with an unusable ID token" }
        }
    }
}
