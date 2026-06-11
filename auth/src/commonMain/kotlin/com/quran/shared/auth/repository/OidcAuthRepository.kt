package com.quran.shared.auth.repository

import co.touchlab.kermit.Logger
import com.quran.shared.auth.di.AuthFlowFactoryProvider
import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.model.TokenResponse
import com.quran.shared.auth.model.UserInfo
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
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse

/**
 * AuthRepository implementation that uses the OIDC library's CodeAuthFlow.
 */
@SingleIn(AppScope::class)
@HiddenFromObjC
class OidcAuthRepository @Inject constructor(
    private val authConfig: AuthConfig,
    private val authStorage: AuthStorage,
    private val oidcClient: OpenIdConnectClient,
    private val networkDataSource: AuthNetworkDataSource,
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
            val storedGeneration = storeTokenResponseIfGenerationCurrent(
                response = response,
                replaceSession = true,
                expectedGeneration = exchange.generation
            ) ?: throw LoginSupersededException()
            withContext(NonCancellable) {
                onDurableCommit()
            }
            fetchAndStoreUserInfo(storedGeneration)
            if (!isGenerationCurrentAndPublishable(storedGeneration)) {
                throw LoginSupersededException()
            }
        } catch (e: CancellationException) {
            failure = e
            throw e
        } catch (e: Exception) {
            failure = e
            logger.e(e) { "Login failed" }
            throw e
        } finally {
            withContext(NonCancellable) {
                endTokenExchange(exchange.id, failure)
            }
        }
    }

    override suspend fun refreshTokensIfNeeded(): Boolean =
        refreshMutex.withLock refreshLock@ {
            val refreshState = sessionMutex.withLock {
                persistedSessionWriteMutex.withLock {
                    refreshSessionStateFromStorageLocked()
                    if (!isCurrentSessionCommittedAndPublishableLocked()) {
                        null
                    } else {
                        RefreshState(
                            refreshToken = authStorage.retrieveStoredRefreshToken(),
                            expirationTime = authStorage.retrieveTokenExpiration(),
                            generation = sessionGeneration
                        )
                    }
                }
            } ?: return@refreshLock false

            val currentTime = currentTimeMillis()
            // If current time is before the safety window, no refresh needed.
            if (currentTime < refreshState.expirationTime - REFRESH_SAFETY_MARGIN_MS) {
                return@refreshLock true
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
                logger.e(e) { "Token refresh failed, clearing session" }
                // Critical: If refresh fails, session is invalid unless logout/login already moved the session on.
                val invalidationResult = sessionMutex.withLock {
                    invalidateLocalSessionIfPersistedGenerationCurrentLocked(refreshState.generation)
                }
                invalidationResult?.failure?.let { throw it }
                return@refreshLock false
            }

            val storedGeneration = storeTokenResponseIfGenerationCurrent(
                response = response,
                replaceSession = false,
                expectedGeneration = refreshState.generation
            ) ?: return@refreshLock false
            fetchAndStoreUserInfo(storedGeneration)
            if (!isGenerationCurrentAndPublishable(storedGeneration)) {
                return@refreshLock false
            }
            true
        }

    private data class RefreshState(
        val refreshToken: String?,
        val expirationTime: Long,
        val generation: Long
    )

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
        replaceSession: Boolean,
        expectedGeneration: Long
    ): Long? =
        tokenExchangeMutex.withLock {
            sessionMutex.withLock {
                persistedSessionWriteMutex.withLock {
                    refreshSessionStateFromStorageLocked()
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
                                replaceSession = replaceSession,
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
        replaceSession: Boolean,
        commitGeneration: Long
    ): Long {
        val tokenResponse = TokenResponse.fromOidc(response)
        if (replaceSession) {
            authStorage.storeNewSessionTokens(tokenResponse)
        } else {
            authStorage.storeTokens(tokenResponse)
        }
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
        val failures = attemptRemoteLogout(tokenMaterial)
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

    override suspend fun attemptRemoteLogout(tokenMaterial: LogoutTokenMaterial): List<RemoteLogoutFailure> {
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

        if (AuthFlowFactoryProvider.isInitialized()) {
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
                authStorage.retrieveUserInfo() ?: tryParseUserFromTokenLocked()
            }
        }

    private suspend fun fetchAndStoreUserInfo(expectedGeneration: Long) {
        try {
            val token = sessionMutex.withLock {
                persistedSessionWriteMutex.withLock {
                    refreshSessionStateFromStorageLocked()
                    if (sessionGeneration != expectedGeneration || !isCurrentSessionCommittedAndPublishableLocked()) {
                        return
                    }
                    authStorage.retrieveStoredAccessToken()
                }
            } ?: return
            val userInfo = networkDataSource.fetchUserInfo(token)
            sessionMutex.withLock {
                persistedSessionWriteMutex.withLock {
                    refreshSessionStateFromStorageLocked()
                    if (
                        sessionGeneration == expectedGeneration &&
                        isCurrentSessionCommittedAndPublishableLocked()
                    ) {
                        authStorage.storeUserInfo(userInfo)
                    }
                }
            }
        } catch (e: CancellationException) {
            val stillPublishable = withContext(NonCancellable) {
                isGenerationCurrentAndPublishable(expectedGeneration)
            }
            if (stillPublishable) {
                return
            }
            throw e
        } catch (e: Exception) {
            logger.e(e) { "Failed to fetch user info from network" }
            // Non-critical: Login succeeded but profile fetch failed.
        }
    }

    private suspend fun isGenerationCurrentAndPublishable(expectedGeneration: Long): Boolean =
        sessionMutex.withLock {
            persistedSessionWriteMutex.withLock {
                refreshSessionStateFromStorageLocked()
                sessionGeneration == expectedGeneration && isCurrentSessionCommittedAndPublishableLocked()
            }
        }

    private suspend fun tryParseUserFromTokenLocked(): UserInfo? {
        val idToken = authStorage.retrieveStoredIdToken()
            ?: authStorage.retrieveStoredAccessToken()
            ?: return null
        return UserInfo.fromJwt(idToken)
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
