package com.quran.shared.auth.repository

import co.touchlab.kermit.Logger
import com.quran.shared.auth.di.AuthFlowFactoryProvider
import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.model.IdTokenUser
import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.model.parseIdTokenUser
import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.auth.utils.currentTimeMillis
import com.quran.shared.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlin.io.encoding.Base64
import kotlin.native.HiddenFromObjC
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectException
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse

/**
 * AuthRepository implementation that uses the OIDC library's CodeAuthFlow.
 */
@SingleIn(AppScope::class)
@HiddenFromObjC
@Inject
class OidcAuthRepository(
    private val authConfig: AuthConfig,
    private val authStorage: AuthStorage,
    private val oidcClient: OpenIdConnectClient,
    private val logger: Logger = Logger.withTag("OidcAuthRepository")
) : AuthRepositoryLoginCommitCallbacks {

    private val refreshMutex = Mutex()
    private val sessionMutex = Mutex()
    private var sessionGeneration = 0L
    private var committedTokenGeneration: Long? = null
    private var tokenWriteGeneration = 0L
    private var committedTokenWriteGeneration: Long? = null

    // Safety margin to refresh tokens before they actually expire (5 minutes)
    private val REFRESH_SAFETY_MARGIN_MS = 5 * 60_000

    private val configureTokenExchange: HttpRequestBuilder.() -> Unit = {
        authConfig.clientSecret?.let { secret ->
            val auth = Base64.encode("${authConfig.clientId}:$secret".encodeToByteArray())
            header(HttpHeaders.Authorization, "Basic $auth")
        }
    }

    override suspend fun getAuthHeaders(): Map<String, String> {
        if (!refreshTokensIfNeeded()) {
            return emptyMap()
        }

        val token = getAccessToken()
        return if (token != null) {
            mapOf(
                "Authorization" to "Bearer $token",
                "x-auth-token" to token,
                "x-client-id" to authConfig.clientId
            )
        } else {
            emptyMap()
        }
    }

    override suspend fun login() {
        login(forcePrompt = false, onDurableCommit = {})
    }

    override suspend fun login(onDurableCommit: suspend () -> Unit) {
        login(forcePrompt = false, onDurableCommit = onDurableCommit)
    }

    override suspend fun loginWithReauthentication() {
        login(forcePrompt = true, onDurableCommit = {})
    }

    override suspend fun loginWithReauthentication(onDurableCommit: suspend () -> Unit) {
        login(forcePrompt = true, onDurableCommit = onDurableCommit)
    }

    private suspend fun login(
        forcePrompt: Boolean,
        onDurableCommit: suspend () -> Unit
    ) {
        val intent = if (forcePrompt) {
            TokenExchangeIntent.Reauthenticate
        } else {
            TokenExchangeIntent.Normal
        }
        val exchange = beginReplacementTokenExchange(intent) ?: return
        var failure: Throwable? = null
        try {
            val authFlow = AuthFlowFactoryProvider.factory.createAuthFlow(oidcClient)
            val response = authFlow.getAccessToken(
                configureAuthUrl = {
                    if (forcePrompt) {
                        parameters.append("prompt", "login")
                    }
                },
                configureTokenExchange = configureTokenExchange
            )
            // The account comes from the ID token, so a session is stored only with a user it can sync for.
            val userInfo = when (val idTokenUser = response.id_token?.let { parseIdTokenUser(it, authConfig) }) {
                is IdTokenUser.Valid -> idTokenUser.userInfo
                is IdTokenUser.Rejected -> throw InvalidIdTokenException(idTokenUser.reason)
                null -> throw InvalidIdTokenException("the token response has no ID token")
            }
            val storedGeneration = storeTokenResponseIfGenerationCurrent(
                response = response,
                write = TokenWrite.NewSession(userInfo),
                expectedGeneration = exchange.generation
            ) ?: throw LoginSupersededException()
            withContext(NonCancellable) {
                onDurableCommit()
            }
            if (!isGenerationCurrentAndPublishable(storedGeneration)) {
                throw LoginSupersededException()
            }
        } catch (e: CancellationException) {
            failure = e
            throw e
        } catch (e: Exception) {
            failure = e
            if (e.isExpectedLoginEnd()) {
                logger.i { "Login ended without a session: ${e::class.simpleName}" }
            } else {
                logger.e(e) { "Login failed" }
            }
            throw e
        } finally {
            withContext(NonCancellable) {
                endTokenExchange(exchange.id, failure)
            }
        }
    }

    override suspend fun refreshTokensIfNeeded(): Boolean = refreshTokens(force = false)

    override suspend fun forceRefreshTokens(): Boolean = refreshTokens(force = true)

    /**
     * Refreshes the token when it is inside the expiry safety margin, or always when [force]d. A refused refresh
     * token ends the session; a transient failure (network, a server error) keeps it and is thrown.
     *
     * A refreshed ID token must be valid and name the session's account. Uploads go to whichever account the access
     * token belongs to, so tokens for another account would mix two accounts' data: such a response ends the session.
     * Its profile claims replace the stored user's. Without an ID token the stored user is kept.
     */
    private suspend fun refreshTokens(force: Boolean): Boolean =
        refreshMutex.withLock refreshLock@ {
            val refreshState = sessionMutex.withLock {
                persistedSessionWriteMutex.withLock {
                    refreshSessionStateFromStorageLocked()
                    if (!isCurrentSessionCommittedAndPublishableLocked()) {
                        return@refreshLock false
                    }
                    // If current time is before the safety window, no refresh needed.
                    val expirationTime = authStorage.retrieveTokenExpiration()
                    if (!force && currentTimeMillis() < expirationTime - REFRESH_SAFETY_MARGIN_MS) {
                        return@refreshLock true
                    }
                    RefreshState(
                        refreshToken = authStorage.retrieveStoredRefreshToken(),
                        generation = sessionGeneration,
                        accountId = authStorage.retrieveUserInfo()?.id
                    )
                }
            }

            val refreshToken = refreshState.refreshToken
            if (refreshToken == null) {
                logger.e { "Token refresh required but no refresh token is stored, clearing session" }
                val invalidationResult = sessionMutex.withLock {
                    invalidateLocalSessionIfPersistedGenerationCurrentLocked(refreshState.generation)
                }
                invalidationResult?.failure?.let { throw it }
                return@refreshLock false
            }

            val response = try {
                oidcClient.refreshToken(
                    refreshToken = refreshToken,
                    configure = configureTokenExchange
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isTransientRefreshFailure()) {
                    logger.w(e) { "Token refresh failed for a transient reason; keeping the session" }
                    throw e
                }
                logger.e(e) { "Token refresh failed, clearing session" }
                // Critical: If refresh fails, session is invalid unless logout/login already moved the session on.
                val invalidationResult = sessionMutex.withLock {
                    invalidateLocalSessionIfPersistedGenerationCurrentLocked(refreshState.generation)
                }
                invalidationResult?.failure?.let { throw it }
                return@refreshLock false
            }

            val refreshedUser = when (val idTokenUser = response.id_token?.let { parseIdTokenUser(it, authConfig) }) {
                null -> null
                is IdTokenUser.Valid -> idTokenUser.userInfo
                is IdTokenUser.Rejected ->
                    return@refreshLock endSessionForUnusableRefresh(refreshState.generation, idTokenUser.reason)
            }
            if (refreshedUser != null && refreshState.accountId != null && refreshedUser.id != refreshState.accountId) {
                return@refreshLock endSessionForUnusableRefresh(
                    refreshState.generation,
                    "the refreshed ID token names another account"
                )
            }

            val storedGeneration = storeTokenResponseIfGenerationCurrent(
                response = response,
                write = TokenWrite.Refresh(refreshedUser),
                expectedGeneration = refreshState.generation
            ) ?: return@refreshLock false
            if (!isGenerationCurrentAndPublishable(storedGeneration)) {
                return@refreshLock false
            }
            true
        }

    /** Ends the session, unless logout or login already moved it on, after a refresh whose ID token is unusable. */
    private suspend fun endSessionForUnusableRefresh(generation: Long, reason: String): Boolean {
        logger.e { "Token refresh returned an unusable ID token, clearing session: $reason" }
        val invalidationResult = sessionMutex.withLock {
            invalidateLocalSessionIfPersistedGenerationCurrentLocked(generation)
        }
        invalidationResult?.failure?.let { throw it }
        return false
    }

    /**
     * [accountId] is the stored user's id. It is null only for a session stored before the user came from the ID token;
     * a valid refreshed ID token then becomes its user.
     */
    private data class RefreshState(
        val refreshToken: String?,
        val generation: Long,
        val accountId: String?
    )

    /** How a token response is stored: as a new session, which needs its user, or as a refresh of the current one. */
    private sealed interface TokenWrite {
        data class NewSession(val user: UserInfo) : TokenWrite

        /** [user] is null when the refresh response has no ID token; the stored user is then kept. */
        data class Refresh(val user: UserInfo?) : TokenWrite
    }

    private data class TokenExchange(
        val id: Long,
        val generation: Long
    )

    private data class TokenExchangeWait(
        val exchange: ActiveTokenExchange,
        val shareResult: Boolean
    )

    private data class ActiveTokenExchange(
        val generation: Long,
        val intent: TokenExchangeIntent,
        val completion: CompletableDeferred<Unit>,
        var committedGeneration: Long? = null
    )

    private data class SessionInvalidationResult(
        val generation: Long,
        val failure: Exception?
    )

    private enum class TokenExchangeIntent {
        Normal,
        Reauthenticate
    }

    private class LoginSupersededException : IllegalStateException("Login was superseded by a newer session lifecycle change")

    private class InvalidIdTokenException(reason: String) : IllegalStateException("Sign-in failed: $reason")

    /**
     * A login the user cancelled at the system prompt, or one a newer session change superseded, is not a fault.
     * Hosts forward warnings and errors to crash reporting, so it logs at info without the throwable.
     */
    private fun Exception.isExpectedLoginEnd(): Boolean =
        this is OpenIdConnectException.AuthenticationCancelled || this is LoginSupersededException

    private suspend fun beginReplacementTokenExchange(intent: TokenExchangeIntent): TokenExchange? {
        while (true) {
            var waitForExchange: TokenExchangeWait? = null
            val exchange = tokenExchangeMutex.withLock tokenLock@ {
                sessionMutex.withLock {
                    persistedSessionWriteMutex.withLock {
                        refreshSessionStateFromStorageLocked()
                        removeActiveTokenExchangesMatching { exchange ->
                            exchange.completion.isCompleted
                        }
                        val activeExchange = activeTokenExchanges.values
                            .firstOrNull { exchange -> !exchange.completion.isCompleted }
                        if (activeExchange != null) {
                            waitForExchange = TokenExchangeWait(
                                exchange = activeExchange,
                                shareResult = activeExchange.intent == intent
                            )
                            return@tokenLock null
                        }

                        val exchangeId = ++nextTokenExchangeId
                        val completion = CompletableDeferred<Unit>()
                        val pendingGeneration = nextLifecycleGenerationLocked()
                        activeTokenExchanges[exchangeId] = ActiveTokenExchange(
                            generation = pendingGeneration,
                            intent = intent,
                            completion = completion
                        )
                        TokenExchange(
                            id = exchangeId,
                            generation = pendingGeneration
                        )
                    }
                }
            }
            if (exchange != null) {
                return exchange
            }
            val wait = waitForExchange ?: return null
            if (wait.shareResult) {
                try {
                    wait.exchange.completion.await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    if (!isCommittedExchangeStillPublishable(wait.exchange)) {
                        throw e
                    }
                }
                return null
            }
            awaitExchangeBeforeRetry(wait.exchange.completion)
        }
    }

    private suspend fun isCommittedExchangeStillPublishable(exchange: ActiveTokenExchange): Boolean {
        val committedGeneration = exchange.committedGeneration ?: return false
        return isGenerationCurrentAndPublishable(committedGeneration)
    }

    private fun removeActiveTokenExchangesMatching(predicate: (ActiveTokenExchange) -> Boolean) {
        val removed = activeTokenExchanges.entries
            .filter { (_, exchange) -> predicate(exchange) }
        removed.forEach { (id, exchange) ->
            activeTokenExchanges.remove(id)
            if (!exchange.completion.isCompleted) {
                exchange.completion.completeExceptionally(LoginSupersededException())
            }
        }
    }

    private suspend fun awaitExchangeBeforeRetry(completion: CompletableDeferred<Unit>) {
        try {
            completion.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
        } catch (e: LoginSupersededException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
        }
    }

    private suspend fun endTokenExchange(exchangeId: Long, failure: Throwable?) {
        tokenExchangeMutex.withLock {
            val completion = activeTokenExchanges.remove(exchangeId)?.completion ?: return@withLock
            if (failure == null) {
                completion.complete(Unit)
            } else {
                completion.completeExceptionally(failure)
            }
        }
    }

    private fun completeActiveTokenExchangesAtOrBeforeLocked(generation: Long) {
        removeActiveTokenExchangesMatching { exchange ->
            exchange.generation <= generation
        }
    }

    private suspend fun storeTokenResponseIfGenerationCurrent(
        response: AccessTokenResponse,
        write: TokenWrite,
        expectedGeneration: Long
    ): Long? =
        tokenExchangeMutex.withLock {
            sessionMutex.withLock {
                persistedSessionWriteMutex.withLock {
                    refreshSessionStateFromStorageLocked()
                    val replaceSession = write is TokenWrite.NewSession
                    val canStore = if (replaceSession) {
                        activeTokenExchanges.values.any { exchange ->
                            exchange.generation == expectedGeneration &&
                                !exchange.completion.isCompleted
                        }
                    } else {
                        sessionGeneration == expectedGeneration &&
                            committedTokenGeneration == expectedGeneration &&
                            isCurrentSessionCommittedAndPublishableLocked()
                    }
                    if (!canStore) {
                        null
                    } else {
                        val storedGeneration = withContext(NonCancellable) {
                            storeTokenResponseLocked(
                                response = response,
                                write = write,
                                commitGeneration = expectedGeneration
                            )
                        }
                        if (replaceSession) {
                            activeTokenExchanges.values
                                .firstOrNull { exchange -> exchange.generation == expectedGeneration }
                                ?.committedGeneration = storedGeneration
                        }
                        storedGeneration
                    }
                }
            }
        }

    private suspend fun invalidateLocalSessionLocked(): SessionInvalidationResult {
        return persistedSessionWriteMutex.withLock {
            refreshSessionStateFromStorageLocked()
            invalidateLocalSessionWithoutPersistedGateLocked()
        }
    }

    private suspend fun invalidateLocalSessionIfPersistedGenerationCurrentLocked(
        expectedGeneration: Long
    ): SessionInvalidationResult? {
        return persistedSessionWriteMutex.withLock {
            refreshSessionStateFromStorageLocked()
            if (sessionGeneration != expectedGeneration) {
                null
            } else {
                invalidateLocalSessionWithoutPersistedGateLocked()
            }
        }
    }

    private suspend fun invalidateLocalSessionWithoutPersistedGateLocked(): SessionInvalidationResult {
        val nextGeneration = nextLifecycleGenerationLocked()
        sessionGeneration = nextGeneration
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

        attempt { authStorage.clearTokenCommitMetadata() }
        attempt { authStorage.storeSessionGeneration(nextGeneration) }
        attempt { authStorage.clearAllTokens() }

        return SessionInvalidationResult(
            generation = nextGeneration,
            failure = failure
        )
    }

    private suspend fun storeTokenResponseLocked(
        response: AccessTokenResponse,
        write: TokenWrite,
        commitGeneration: Long
    ): Long {
        val tokenResponse = TokenResponse.fromOidc(response)
        when (write) {
            is TokenWrite.NewSession -> authStorage.storeNewSessionTokens(tokenResponse, write.user)
            is TokenWrite.Refresh -> authStorage.storeTokens(tokenResponse, write.user)
        }
        val replaceSession = write is TokenWrite.NewSession
        tokenWriteGeneration = authStorage.retrieveTokenWriteGeneration()
        if (replaceSession && sessionGeneration != commitGeneration) {
            authStorage.storeSessionGeneration(commitGeneration)
            sessionGeneration = commitGeneration
        }
        authStorage.storeCommittedTokenGeneration(commitGeneration)
        committedTokenGeneration = commitGeneration
        committedTokenWriteGeneration = tokenWriteGeneration
        return commitGeneration
    }

    override suspend fun logout() {
        val tokenMaterial = captureLogoutTokenMaterialAndClearLocalSession()
        val failures = attemptRemoteLogout(
            tokenMaterial,
            RemoteLogoutMode.TOKEN_REVOCATION_ONLY
        )
        failures.forEach { failure ->
            logger.w(failure.exception) { "Remote logout cleanup failed: ${failure.operation}" }
        }
    }

    override suspend fun captureLogoutTokenMaterial(): LogoutTokenMaterial =
        sessionMutex.withLock {
            persistedSessionWriteMutex.withLock {
                refreshSessionStateFromStorageLocked()
                if (!isCurrentSessionCommittedAndPublishableLocked()) {
                    return@withLock LogoutTokenMaterial(refreshToken = null, idToken = null)
                }
                LogoutTokenMaterial(
                    refreshToken = authStorage.retrieveStoredRefreshToken(),
                    idToken = authStorage.retrieveStoredIdToken()
                )
            }
        }

    override suspend fun captureLogoutTokenMaterialAndClearLocalSession(): LogoutTokenMaterial {
        var tokenMaterial: LogoutTokenMaterial? = null
        var captureFailure: Exception? = null
        var invalidationResult: SessionInvalidationResult? = null
        tokenExchangeMutex.withLock {
            sessionMutex.withLock {
                persistedSessionWriteMutex.withLock {
                    refreshSessionStateFromStorageLocked()
                    try {
                        tokenMaterial = if (isCurrentSessionCommittedAndPublishableLocked()) {
                            LogoutTokenMaterial(
                                refreshToken = authStorage.retrieveStoredRefreshToken(),
                                idToken = authStorage.retrieveStoredIdToken()
                            )
                        } else {
                            LogoutTokenMaterial(refreshToken = null, idToken = null)
                        }
                    } catch (e: Exception) {
                        captureFailure = e
                    } finally {
                        invalidationResult = invalidateLocalSessionWithoutPersistedGateLocked()
                    }
                }
            }
            invalidationResult?.let { result ->
                completeActiveTokenExchangesAtOrBeforeLocked(result.generation)
            }
        }
        invalidationResult?.let { result ->
            result.failure?.let { throw it }
        }
        captureFailure?.let { throw LogoutTokenCaptureException(it) }
        return requireNotNull(tokenMaterial)
    }

    override suspend fun clearLocalSession() {
        val invalidationResult = tokenExchangeMutex.withLock {
            val result = sessionMutex.withLock {
                invalidateLocalSessionLocked()
            }
            completeActiveTokenExchangesAtOrBeforeLocked(result.generation)
            result
        }
        invalidationResult.failure?.let { throw it }
    }

    override suspend fun attemptRemoteLogout(
        tokenMaterial: LogoutTokenMaterial,
        mode: RemoteLogoutMode
    ): List<RemoteLogoutFailure> {
        val failures = mutableListOf<RemoteLogoutFailure>()

        tokenMaterial.refreshToken?.let { refreshToken ->
            try {
                oidcClient.revokeToken(refreshToken, configureTokenExchange)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                exception.cancellationCauseOrNull()?.let { throw it }
                failures += RemoteLogoutFailure(RemoteLogoutOperation.REVOKE_REFRESH_TOKEN, exception)
            }
        }

        if (
            RemoteLogoutOperation.END_SESSION in mode.operations &&
            AuthFlowFactoryProvider.isInitialized()
        ) {
            try {
                val endSessionFlow = AuthFlowFactoryProvider.factory.createEndSessionFlow(oidcClient)
                endSessionFlow.endSession(tokenMaterial.idToken) {
                    // optional post-logout actions
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                exception.cancellationCauseOrNull()?.let { throw it }
                failures += RemoteLogoutFailure(RemoteLogoutOperation.END_SESSION, exception)
            }
        }

        return failures
    }

    override suspend fun getAccessToken(): String? =
        sessionMutex.withLock {
            retrieveCurrentAccessTokenLocked()
        }

    override suspend fun isLoggedIn(): Boolean = getAccessToken() != null

    override suspend fun getCurrentUser(): UserInfo? =
        sessionMutex.withLock {
            persistedSessionWriteMutex.withLock {
                refreshSessionStateFromStorageLocked()
                if (!isCurrentSessionCommittedAndPublishableLocked()) {
                    return@withLock null
                }
                authStorage.retrieveUserInfo()
            }
        }

    private suspend fun isGenerationCurrentAndPublishable(expectedGeneration: Long): Boolean =
        sessionMutex.withLock {
            persistedSessionWriteMutex.withLock {
                refreshSessionStateFromStorageLocked()
                sessionGeneration == expectedGeneration && isCurrentSessionCommittedAndPublishableLocked()
            }
        }

    private suspend fun refreshSessionStateFromStorageLocked() {
        sessionGeneration = authStorage.retrieveSessionGeneration()
        committedTokenGeneration = authStorage.retrieveCommittedTokenGeneration()
        tokenWriteGeneration = authStorage.retrieveTokenWriteGeneration()
        committedTokenWriteGeneration = authStorage.retrieveCommittedTokenWriteGeneration()
    }

    private suspend fun retrieveCurrentAccessTokenLocked(): String? {
        return persistedSessionWriteMutex.withLock {
            refreshSessionStateFromStorageLocked()
            if (!isCurrentSessionCommittedAndPublishableLocked()) {
                null
            } else {
                authStorage.retrieveStoredAccessToken()
            }
        }
    }

    private fun nextLifecycleGenerationLocked(): Long =
        sessionGeneration + 1

    private fun isCurrentSessionCommittedAndPublishableLocked(): Boolean =
        committedTokenGeneration == sessionGeneration &&
            committedTokenWriteGeneration == tokenWriteGeneration

    companion object {
        private val tokenExchangeMutex = Mutex()
        private val activeTokenExchanges = mutableMapOf<Long, ActiveTokenExchange>()
        private var nextTokenExchangeId = 0L
        private val persistedSessionWriteMutex = Mutex()
    }
}

private fun Throwable.cancellationCauseOrNull(): CancellationException? {
    var current: Throwable? = this
    while (current != null) {
        if (current is CancellationException) {
            return current
        }
        current = current.cause
    }
    return null
}

/**
 * A refresh that failed without the authorization server refusing the refresh token: no network, a timeout, or a
 * server error. Only a refusal (an OAuth error response such as `invalid_grant`) ends the session.
 */
internal fun Exception.isTransientRefreshFailure(): Boolean = when (this) {
    // The OIDC library wraps every non-HTTP failure in TechnicalFailure; only a network error or a timeout is transient.
    is OpenIdConnectException.TechnicalFailure -> generateSequence(cause) { it.cause }.any { it.isNetworkOrTimeout() }
    is OpenIdConnectException.UnsuccessfulTokenRequest ->
        statusCode.value >= 500 || statusCode.value == 408 || statusCode.value == 429
    is kotlinx.io.IOException -> true
    else -> false
}

private fun Throwable.isNetworkOrTimeout(): Boolean =
    this is kotlinx.io.IOException || this::class.simpleName.orEmpty().contains("Timeout")
