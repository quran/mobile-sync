package com.quran.shared.auth.service

import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.model.AuthenticationCancelledException
import com.quran.shared.auth.model.FetchedProfile
import com.quran.shared.auth.model.FetchedProfileWrite
import com.quran.shared.auth.model.ProfileFetchSession
import com.quran.shared.auth.model.asAuthenticationException
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.AuthRepositoryLoginCommitCallbacks
import com.quran.shared.auth.repository.LogoutTokenCaptureException
import com.quran.shared.auth.repository.LogoutTokenMaterial
import com.quran.shared.auth.repository.RemoteLogoutFailure
import com.quran.shared.auth.repository.RemoteLogoutMode
import com.quran.shared.di.AppScope
import dev.zacsweers.metro.SingleIn
import kotlin.native.HiddenFromObjC
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Shared service for authentication logic and state.
 *
 * This service centralizes authentication operations and maintains the authentication state,
 * making it easy to share across platforms while allowing native ViewModels (iOS/Android)
 * to handle platform-specific UI concerns.
 */
@HiddenFromObjC
fun interface AuthSessionPublicationGuard {
    suspend fun canPublishStoredSession(): Boolean

    companion object {
        val Allow = AuthSessionPublicationGuard { true }
    }
}

/**
 * A session as [AuthService.sessionStart] reports it when it is first published: [interactive] for a sign-in through
 * the browser, false for a stored session restored (at launch, or lazily by the first request that needs it).
 * [generation] tells sessions apart, so two sign-ins in a row are two values.
 */
@HiddenFromObjC
data class SessionStart(val generation: Long, val interactive: Boolean)

@SingleIn(AppScope::class)
@HiddenFromObjC
class AuthService private constructor(
    private val authRepository: AuthRepository,
    private val sessionPublicationGuard: AuthSessionPublicationGuard
) {
    constructor(authRepository: AuthRepository) : this(authRepository, AuthSessionPublicationGuard.Allow)

    companion object {
        /**
         * Creates an AuthService with an explicit stored-session publication guard.
         *
         * This is used by managed graph lifecycle code and tests to block stored-session
         * publication while a full local reset is in progress.
         */
        @HiddenFromObjC
        fun createWithSessionPublicationGuard(
            authRepository: AuthRepository,
            sessionPublicationGuard: AuthSessionPublicationGuard
        ): AuthService = AuthService(authRepository, sessionPublicationGuard)
    }

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    private var cachedAccessToken: String? = null
    private val sessionPublicationMutex = Mutex()
    private var lifecycle: AuthPublicationLifecycle = AuthPublicationLifecycle.Idle(generation = 0)

    /**
     * The session's state. [AuthState.Success]'s user is the ID token's, with the profile fetched from the account API
     * on top ([storeFetchedProfile]). A token refresh can change the stored user (its ID token's profile claims replace
     * the stored ones), so [AuthState.Success] is republished with the new user after a refresh, whether it ran through
     * [refreshAccessTokenIfNeeded], [forceRefreshAccessToken] or [getAuthHeaders]; a fetched profile still shows over
     * it. Storing a fetched profile republishes it too. Each republish emits only when the user changed.
     */
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _sessionStart = MutableStateFlow<SessionStart?>(null)

    /**
     * How the current session began, for the sync pipeline: it changes once per session, as [authState] first shows it
     * (set first, under the same lock), and keeps its value when a token refresh publishes the session again or the
     * session ends. Null until a session is published.
     */
    @HiddenFromObjC
    val sessionStart: StateFlow<SessionStart?> = _sessionStart.asStateFlow()

    init {
        scope.launch {
            checkCurrentSession()
        }
    }

    /**
     * Cancels and waits for work owned by this service's background scope.
     *
     * Call this when disposing the app graph or a test fixture before resetting the Main
     * dispatcher. It only covers work launched by [AuthService] itself, such as stored-session
     * startup checks and retrying error recovery; caller-owned login/logout coroutines remain
     * owned by their caller.
     */
    @HiddenFromObjC
    suspend fun clearAndJoin() {
        serviceJob.cancelAndJoin()
    }

    private suspend fun checkCurrentSession() {
        val generation = idleGenerationOrNull() ?: return
        when (tryPublishValidatedStoredSessionFromIdle(generation)) {
            PublishSessionResult.Published, PublishSessionResult.Stale -> Unit
            PublishSessionResult.NoSession -> clearIdleIfGenerationCurrent(generation)
        }
    }

    /**
     * Signs in through the browser, asking the identity provider for credentials again (`prompt=login`).
     * A call made while another sign-in runs waits on that one instead of opening the browser again.
     */
    suspend fun login() {
        try {
            val loginReservation = reserveLoginOrAwaitActive() ?: return
            runActiveLogin(loginReservation) {
                runRepositoryLogin(loginReservation)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e.asAuthenticationException()
        }
    }

    private suspend fun runRepositoryLogin(loginReservation: LoginReservation) {
        val commitAwareRepository = authRepository as? AuthRepositoryLoginCommitCallbacks
        if (commitAwareRepository != null) {
            commitAwareRepository.login(onDurableCommit = {
                completeRepositoryCommitIfLoadingCurrent(loginReservation)
            })
        } else {
            authRepository.login()
            completeRepositoryCommitIfLoadingCurrent(loginReservation)
        }
    }

    /** Reserves a new login, or waits on the one already running and returns null. */
    private suspend fun reserveLoginOrAwaitActive(): LoginReservation? {
        var loginReservation: LoginReservation? = null
        val existingLogin = sessionPublicationMutex.withLock {
            when (val current = lifecycle) {
                is AuthPublicationLifecycle.Loading -> current.activeLogin
                is AuthPublicationLifecycle.Clearing -> throw LoginInvalidatedException()
                is AuthPublicationLifecycle.Idle,
                is AuthPublicationLifecycle.Authenticated -> {
                    val generation = current.generation + 1
                    val completion = CompletableDeferred<Unit>()
                    val repositoryCommit = CompletableDeferred<Unit>()
                    val activeLogin = ActiveLogin(
                        completion = completion,
                        generation = generation,
                        repositoryCommit = repositoryCommit
                    )
                    cachedAccessToken = null
                    _authState.value = AuthState.Loading
                    lifecycle = AuthPublicationLifecycle.Loading(
                        generation = generation,
                        activeLogin = activeLogin
                    )
                    loginReservation = LoginReservation(
                        completion = completion,
                        generation = generation,
                        repositoryCommit = repositoryCommit
                    )
                    null
                }
            }
        }
        if (existingLogin == null) {
            return loginReservation
        }
        try {
            existingLogin.completion.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        }
        return null
    }

    private suspend fun runActiveLogin(
        loginReservation: LoginReservation,
        repositoryLogin: suspend () -> Unit
    ) = coroutineScope {
        val repositoryLoginJob = async(start = CoroutineStart.LAZY) {
            repositoryLogin()
        }
        var repositoryLoginCompleted = false
        try {
            if (!attachRepositoryLoginJob(loginReservation, repositoryLoginJob)) {
                repositoryLoginJob.cancel()
                throw LoginInvalidatedException()
            }
            repositoryLoginJob.start()
            repositoryLoginJob.await()
            completeRepositoryCommitIfLoadingCurrent(loginReservation)
            repositoryLoginCompleted = true
            when (publishValidatedActiveLoginSession(loginReservation.generation)) {
                PublishSessionResult.Published -> Unit
                PublishSessionResult.Stale -> throw LoginInvalidatedException()
                PublishSessionResult.NoSession -> {
                    throw Exception("Failed to persist authentication tokens after login")
                }
            }
            withContext(NonCancellable) {
                completeActiveLogin(loginReservation)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                repositoryLoginJob.cancelAndJoin()
                val completionException = if (loginReservation.repositoryCommit.isCompleted) {
                    when (tryPublishActiveLoginSessionForCompletion(loginReservation.generation)) {
                        PublishSessionResult.Published -> null
                        PublishSessionResult.NoSession,
                        PublishSessionResult.Stale -> e
                    }
                } else {
                    e
                }
                completeActiveLogin(
                    loginReservation = loginReservation,
                    exception = completionException
                )
            }
            throw e
        } catch (e: Exception) {
            val authenticationException = e.asAuthenticationException()
            withContext(NonCancellable) {
                val publishResult = if (repositoryLoginCompleted) {
                    tryPublishValidatedActiveLoginSession(loginReservation.generation)
                } else {
                    PublishSessionResult.NoSession
                }
                if (publishResult != PublishSessionResult.Published) {
                    failActiveLoginIfLoadingGenerationCurrent(
                        authenticationException,
                        "Login failed",
                        loginReservation
                    )
                }
                completeActiveLogin(loginReservation, authenticationException)
            }
            throw e
        }
    }

    private suspend fun completeRepositoryCommitIfLoadingCurrent(loginReservation: LoginReservation): Boolean {
        if (loginReservation.repositoryCommit.isCompleted) {
            return true
        }
        return sessionPublicationMutex.withLock {
            val current = lifecycle as? AuthPublicationLifecycle.Loading ?: return@withLock false
            val activeLogin = current.activeLogin
            if (
                activeLogin.completion !== loginReservation.completion ||
                activeLogin.generation != loginReservation.generation
            ) {
                false
            } else {
                loginReservation.repositoryCommit.complete(Unit)
                true
            }
        }
    }

    private suspend fun attachRepositoryLoginJob(
        loginReservation: LoginReservation,
        repositoryLoginJob: Job
    ): Boolean =
        sessionPublicationMutex.withLock {
            val current = lifecycle as? AuthPublicationLifecycle.Loading ?: return@withLock false
            val login = current.activeLogin
            if (
                login.completion !== loginReservation.completion ||
                login.generation != loginReservation.generation
            ) {
                false
            } else {
                lifecycle = current.copy(activeLogin = login.copy(repositoryJob = repositoryLoginJob))
                true
            }
        }

    private suspend fun completeActiveLogin(
        loginReservation: LoginReservation,
        exception: Exception? = null
    ) {
        if (loginReservation.completion.isCompleted) {
            return
        }
        val shouldComplete = sessionPublicationMutex.withLock {
            val current = lifecycle as? AuthPublicationLifecycle.Loading
            if (current?.activeLogin?.completion === loginReservation.completion) {
                cachedAccessToken = null
                lifecycle = AuthPublicationLifecycle.Idle(current.generation + 1)
                _authState.value = AuthState.Idle
                true
            } else {
                false
            }
        }
        if (!shouldComplete) {
            return
        }
        if (exception != null) {
            loginReservation.completion.completeExceptionally(exception)
        } else {
            loginReservation.completion.complete(Unit)
        }
    }

    suspend fun logout(): Unit {
        val tokenMaterial = try {
            captureLogoutTokenMaterialForLogout()
        } catch (_: LogoutTokenCaptureException) {
            null
        } catch (e: Exception) {
            handleError(e, "Logout failed")
            throw e
        }

        if (tokenMaterial != null) {
            try {
                authRepository.attemptRemoteLogout(
                    tokenMaterial,
                    RemoteLogoutMode.TOKEN_REVOCATION_ONLY
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Remote logout is best-effort after local session state is already unpublished.
            }
        }
    }

    @HiddenFromObjC
    suspend fun captureLogoutTokenMaterial(): LogoutTokenMaterial =
        authRepository.captureLogoutTokenMaterial()

    /**
     * Enters the non-publishable clearing lifecycle before draining active login work, then clears
     * persisted tokens. Lazy stored-session publication remains blocked until repository clear
     * has finished and the lifecycle settles back to idle.
     */
    @HiddenFromObjC
    suspend fun captureLogoutTokenMaterialForLogout(): LogoutTokenMaterial {
        return withClearing {
            authRepository.captureLogoutTokenMaterialAndClearLocalSession()
        }
    }

    @HiddenFromObjC
    suspend fun clearLocalSession() {
        withClearing {
            authRepository.clearLocalSession()
        }
    }

    private suspend fun <T> withClearing(block: suspend () -> T): T {
        val clearing = withContext(NonCancellable) {
            beginClearing()
        }
        try {
            return withContext(NonCancellable) {
                block()
            }
        } finally {
            withContext(NonCancellable) {
                settleClearing(clearing)
            }
        }
    }

    @HiddenFromObjC
    suspend fun attemptRemoteLogout(
        tokenMaterial: LogoutTokenMaterial,
        mode: RemoteLogoutMode
    ): List<RemoteLogoutFailure> =
        authRepository.attemptRemoteLogout(tokenMaterial, mode)

    suspend fun refreshAccessTokenIfNeeded(): Boolean {
        return when (val current = lifecycleSnapshot()) {
            is AuthPublicationLifecycle.Idle -> {
                publishValidatedStoredSessionFromIdle(current.generation) == PublishSessionResult.Published
            }
            is AuthPublicationLifecycle.Authenticated -> refreshAuthenticatedSession(current.generation)
            is AuthPublicationLifecycle.Loading,
            is AuthPublicationLifecycle.Clearing -> false
        }
    }

    /**
     * Refreshes the access token now, even though it has not expired locally, because the server rejected it (401).
     * A refused refresh token ends the session, which [authState] then reports; a transient failure keeps the
     * session and is thrown.
     *
     * @return true when a new token is in use; false when there is no session or it ended.
     */
    @HiddenFromObjC
    suspend fun forceRefreshAccessToken(): Boolean {
        val current = lifecycleSnapshot() as? AuthPublicationLifecycle.Authenticated ?: return false
        return refreshAuthenticatedSession(current.generation, force = true)
    }

    /** The signed-in account's stable id (the OIDC `sub`), or null without a session or a known user. */
    @HiddenFromObjC
    suspend fun currentAccountId(): String? {
        if (lifecycleSnapshot() !is AuthPublicationLifecycle.Authenticated) return null
        return authRepository.getCurrentUser()?.id?.takeIf { it.isNotBlank() }
    }

    /**
     * The published session as a profile fetch needs it: its generation, account, and last fetch and attempt. Null
     * without a published session (signed out, or while a sign-in or logout runs) or a known account.
     */
    @HiddenFromObjC
    suspend fun profileFetchSession(): ProfileFetchSession? {
        if (lifecycleSnapshot() !is AuthPublicationLifecycle.Authenticated) return null
        return authRepository.captureProfileFetchSession()
    }

    /**
     * Records that [session] tried to fetch its profile at [attemptedAt] (epoch milliseconds), for the fetch's back-off.
     * Nothing is written once the session ended or changed.
     */
    @HiddenFromObjC
    suspend fun recordProfileFetchAttempt(session: ProfileFetchSession, attemptedAt: Long): Boolean =
        authRepository.recordProfileFetchAttempt(session, attemptedAt)

    /**
     * Stores [profile], fetched for [session], and republishes [authState] when that changed the user. It is dropped,
     * and the session kept, when it names another account or the session changed during the fetch (see
     * [AuthRepository.storeFetchedProfile]).
     */
    @HiddenFromObjC
    suspend fun storeFetchedProfile(session: ProfileFetchSession, profile: FetchedProfile): FetchedProfileWrite {
        val current = lifecycleSnapshot() as? AuthPublicationLifecycle.Authenticated
            ?: return FetchedProfileWrite.SESSION_CHANGED
        val write = authRepository.storeFetchedProfile(session, profile)
        if (write == FetchedProfileWrite.STORED) {
            republishSession(current.generation)
        }
        return write
    }

    private suspend fun refreshAuthenticatedSession(generation: Long, force: Boolean = false): Boolean {
        if (!isPublicationGuardAllowed()) {
            clearAuthenticatedIfGenerationCurrent(generation)
            return false
        }
        val refreshed = try {
            if (force) authRepository.forceRefreshTokens() else authRepository.refreshTokensIfNeeded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reconcileAuthenticatedSessionAfterRefreshFailure(generation)
            throw e
        }
        if (refreshed) {
            when (publishCurrentSession(generation, PublishSource.Authenticated)) {
                PublishSessionResult.Published, PublishSessionResult.Stale -> Unit
                PublishSessionResult.NoSession -> clearAuthenticatedIfGenerationCurrent(generation)
            }
        } else {
            clearAuthenticatedIfGenerationCurrent(generation)
        }
        return refreshed
    }

    private suspend fun reconcileAuthenticatedSessionAfterRefreshFailure(generation: Long) {
        val result = try {
            publishCurrentSession(generation, PublishSource.Authenticated)
        } catch (_: Exception) {
            PublishSessionResult.NoSession
        }
        if (result == PublishSessionResult.NoSession) {
            clearAuthenticatedIfGenerationCurrent(generation)
        }
    }

    fun isLoggedIn(): Boolean = _authState.value is AuthState.Success

    fun getAccessToken(): String? = cachedAccessToken

    /**
     * The headers for an authorized request, refreshing the token first when it is near expiry. When the token was
     * refreshed, the session is republished so [authState] carries the refreshed user.
     *
     * @return an empty map when there is no session or it ended, while a login or logout is in progress, or when the
     * session publication guard blocks the stored session.
     * @throws Exception when a refresh failed for a transient reason (network, server error); the session is kept.
     */
    suspend fun getAuthHeaders(): Map<String, String> {
        return when (val current = lifecycleSnapshot()) {
            is AuthPublicationLifecycle.Authenticated -> getAuthHeadersForAuthenticated(current.generation)
            is AuthPublicationLifecycle.Idle -> {
                when (publishValidatedStoredSessionFromIdle(current.generation)) {
                    PublishSessionResult.Published -> getAuthHeadersForAuthenticated(current.generation)
                    PublishSessionResult.NoSession, PublishSessionResult.Stale -> emptyMap()
                }
            }
            is AuthPublicationLifecycle.Loading,
            is AuthPublicationLifecycle.Clearing -> emptyMap()
        }
    }

    private suspend fun getAuthHeadersForAuthenticated(generation: Long): Map<String, String> {
        if (!isAuthenticatedGenerationCurrent(generation)) {
            return emptyMap()
        }
        if (!isPublicationGuardAllowed()) {
            clearAuthenticatedIfGenerationCurrent(generation)
            return emptyMap()
        }
        val headers = try {
            authRepository.getAuthHeaders()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reconcileAuthenticatedSessionAfterRefreshFailure(generation)
            throw e
        }
        if (!isAuthenticatedGenerationCurrent(generation)) {
            return emptyMap()
        }
        if (!isPublicationGuardAllowed()) {
            clearAuthenticatedIfGenerationCurrent(generation)
            return emptyMap()
        }
        if (headers.isEmpty()) {
            clearAuthenticatedIfGenerationCurrent(generation)
            return emptyMap()
        }
        when (cacheAccessTokenIfAuthenticated(generation)) {
            CachedAccessToken.Unchanged -> Unit
            CachedAccessToken.Rotated -> if (!republishSession(generation)) {
                return emptyMap()
            }
            CachedAccessToken.NotCurrent -> {
                clearAuthenticatedIfGenerationCurrent(generation)
                return emptyMap()
            }
        }
        return if (isAuthenticatedGenerationCurrent(generation)) {
            headers
        } else {
            emptyMap()
        }
    }

    private suspend fun publishValidatedStoredSessionFromIdle(expectedGeneration: Long): PublishSessionResult {
        if (!isIdleGenerationCurrent(expectedGeneration)) {
            return PublishSessionResult.Stale
        }
        if (!isPublicationGuardAllowed()) {
            clearIdleIfGenerationCurrent(expectedGeneration)
            return PublishSessionResult.Stale
        }
        val valid = authRepository.refreshTokensIfNeeded()
        if (!isIdleGenerationCurrent(expectedGeneration)) {
            return PublishSessionResult.Stale
        }
        if (!isPublicationGuardAllowed()) {
            clearIdleIfGenerationCurrent(expectedGeneration)
            return PublishSessionResult.Stale
        }
        if (!valid) {
            clearIdleIfGenerationCurrent(expectedGeneration)
            return PublishSessionResult.NoSession
        }
        return publishCurrentSession(expectedGeneration, PublishSource.Idle)
    }

    private suspend fun publishValidatedActiveLoginSession(expectedGeneration: Long): PublishSessionResult {
        if (!isLoadingGenerationCurrent(expectedGeneration)) {
            return PublishSessionResult.Stale
        }
        if (!isPublicationGuardAllowed()) {
            return PublishSessionResult.Stale
        }
        val valid = authRepository.refreshTokensIfNeeded()
        if (!isLoadingGenerationCurrent(expectedGeneration)) {
            return PublishSessionResult.Stale
        }
        if (!isPublicationGuardAllowed()) {
            return PublishSessionResult.Stale
        }
        if (!valid) {
            return PublishSessionResult.NoSession
        }
        return publishCurrentSession(expectedGeneration, PublishSource.ActiveLogin)
    }

    private suspend fun tryPublishValidatedStoredSessionFromIdle(expectedGeneration: Long): PublishSessionResult =
        try {
            publishValidatedStoredSessionFromIdle(expectedGeneration)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PublishSessionResult.NoSession
        }

    private suspend fun tryPublishValidatedActiveLoginSession(expectedGeneration: Long): PublishSessionResult =
        try {
            publishValidatedActiveLoginSession(expectedGeneration)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PublishSessionResult.NoSession
        }

    private suspend fun tryPublishActiveLoginSessionForCompletion(expectedGeneration: Long): PublishSessionResult =
        try {
            publishValidatedActiveLoginSession(expectedGeneration)
        } catch (_: Exception) {
            PublishSessionResult.NoSession
        }

    /**
     * Publishes an authenticated session when token material is available, regardless of whether
     * profile metadata is currently cached or fetchable.
     */
    private suspend fun publishCurrentSession(
        expectedGeneration: Long,
        source: PublishSource
    ): PublishSessionResult {
        if (!isLifecyclePublishSourceCurrent(expectedGeneration, source)) {
            return PublishSessionResult.Stale
        }
        authRepository.getAccessToken() ?: return if (isLifecyclePublishSourceCurrent(expectedGeneration, source)) {
            PublishSessionResult.NoSession
        } else {
            PublishSessionResult.Stale
        }
        val currentUser = authRepository.getCurrentUser()
        val repositoryLoggedIn = authRepository.isLoggedIn()
        val repositoryAccessToken = if (repositoryLoggedIn) {
            authRepository.getAccessToken()
        } else {
            null
        }

        return sessionPublicationMutex.withLock {
            val activeLogin = (lifecycle as? AuthPublicationLifecycle.Loading)
                ?.activeLogin
                ?.takeIf { source == PublishSource.ActiveLogin }
            if (!lifecycle.matchesPublishSource(expectedGeneration, source)) {
                return@withLock PublishSessionResult.Stale
            }
            if (!repositoryLoggedIn || repositoryAccessToken == null) {
                return@withLock PublishSessionResult.NoSession
            }
            cachedAccessToken = repositoryAccessToken
            lifecycle = AuthPublicationLifecycle.Authenticated(expectedGeneration)
            // A new session (from idle or a sign-in), not a refresh publishing the current one again.
            if (source != PublishSource.Authenticated) {
                _sessionStart.value = SessionStart(expectedGeneration, interactive = source == PublishSource.ActiveLogin)
            }
            _authState.value = AuthState.Success(currentUser)
            activeLogin?.completion?.complete(Unit)
            PublishSessionResult.Published
        }
    }

    fun clearError() {
        if (_authState.value is AuthState.Error) {
            _authState.value = AuthState.Idle
            scope.launch {
                checkCurrentSession()
            }
        }
    }

    private fun handleError(e: Exception, defaultMessage: String) {
        val errorMessage = e.message ?: defaultMessage
        _authState.value = AuthState.Error(e, errorMessage)
    }

    private suspend fun failActiveLoginIfLoadingGenerationCurrent(
        e: Exception,
        defaultMessage: String,
        loginReservation: LoginReservation
    ) {
        val errorMessage = e.message ?: defaultMessage
        sessionPublicationMutex.withLock {
            val current = lifecycle
            if (
                current is AuthPublicationLifecycle.Loading &&
                current.generation == loginReservation.generation &&
                current.activeLogin.completion === loginReservation.completion
            ) {
                cachedAccessToken = null
                lifecycle = AuthPublicationLifecycle.Idle(loginReservation.generation + 1)
                _authState.value = if (e is AuthenticationCancelledException) {
                    AuthState.Idle
                } else {
                    AuthState.Error(e, errorMessage)
                }
                loginReservation.completion.completeExceptionally(e)
            }
        }
    }

    private suspend fun beginClearing(): ClearingReservation {
        val detachedLogin: ActiveLogin?
        val clearingGeneration: Long
        sessionPublicationMutex.withLock {
            detachedLogin = (lifecycle as? AuthPublicationLifecycle.Loading)?.activeLogin
            clearingGeneration = lifecycle.generation + 1
            cachedAccessToken = null
            _authState.value = AuthState.Idle
            lifecycle = AuthPublicationLifecycle.Clearing(clearingGeneration)
        }
        detachedLogin?.completion?.completeExceptionally(LoginInvalidatedException())
        detachedLogin?.repositoryJob?.cancelAndJoin()
        return ClearingReservation(clearingGeneration)
    }

    private suspend fun settleClearing(clearing: ClearingReservation) {
        sessionPublicationMutex.withLock {
            val current = lifecycle
            if (current is AuthPublicationLifecycle.Clearing && current.generation == clearing.generation) {
                cachedAccessToken = null
                _authState.value = AuthState.Idle
                lifecycle = AuthPublicationLifecycle.Idle(clearing.generation + 1)
            }
        }
    }

    private suspend fun clearIdleIfGenerationCurrent(expectedGeneration: Long): Boolean =
        sessionPublicationMutex.withLock {
            val current = lifecycle
            if (current is AuthPublicationLifecycle.Idle && current.generation == expectedGeneration) {
                cachedAccessToken = null
                _authState.value = AuthState.Idle
                lifecycle = AuthPublicationLifecycle.Idle(expectedGeneration + 1)
                true
            } else {
                false
            }
        }

    private suspend fun clearAuthenticatedIfGenerationCurrent(expectedGeneration: Long): Boolean =
        sessionPublicationMutex.withLock {
            val current = lifecycle
            if (current is AuthPublicationLifecycle.Authenticated && current.generation == expectedGeneration) {
                cachedAccessToken = null
                _authState.value = AuthState.Idle
                lifecycle = AuthPublicationLifecycle.Idle(expectedGeneration + 1)
                true
            } else {
                false
            }
        }

    private suspend fun idleGenerationOrNull(): Long? =
        sessionPublicationMutex.withLock {
            (lifecycle as? AuthPublicationLifecycle.Idle)?.generation
        }

    private suspend fun lifecycleSnapshot(): AuthPublicationLifecycle =
        sessionPublicationMutex.withLock { lifecycle }

    private suspend fun isIdleGenerationCurrent(expectedGeneration: Long): Boolean =
        sessionPublicationMutex.withLock {
            val current = lifecycle
            current is AuthPublicationLifecycle.Idle && current.generation == expectedGeneration
        }

    private suspend fun isLoadingGenerationCurrent(expectedGeneration: Long): Boolean =
        sessionPublicationMutex.withLock {
            val current = lifecycle
            current is AuthPublicationLifecycle.Loading && current.generation == expectedGeneration
        }

    private suspend fun isAuthenticatedGenerationCurrent(expectedGeneration: Long): Boolean =
        sessionPublicationMutex.withLock {
            val current = lifecycle
            current is AuthPublicationLifecycle.Authenticated &&
                current.generation == expectedGeneration &&
                cachedAccessToken != null
        }

    private suspend fun isLifecyclePublishSourceCurrent(
        expectedGeneration: Long,
        source: PublishSource
    ): Boolean =
        sessionPublicationMutex.withLock {
            lifecycle.matchesPublishSource(expectedGeneration, source)
        }

    /**
     * Caches the repository's access token for the authenticated [expectedGeneration]. A token other than the cached
     * one means it was refreshed since the session was last published; it is then left for [republishSession]
     * to cache with the user, so a republish that fails or is cancelled is retried on the next call.
     */
    private suspend fun cacheAccessTokenIfAuthenticated(expectedGeneration: Long): CachedAccessToken {
        val accessToken = authRepository.getAccessToken()
        return sessionPublicationMutex.withLock {
            val current = lifecycle
            if (current is AuthPublicationLifecycle.Authenticated && current.generation == expectedGeneration) {
                when (accessToken) {
                    null -> CachedAccessToken.NotCurrent
                    cachedAccessToken -> CachedAccessToken.Unchanged
                    else -> CachedAccessToken.Rotated
                }
            } else {
                CachedAccessToken.NotCurrent
            }
        }
    }

    /**
     * Republishes the authenticated [generation] after its stored user may have changed: [getAuthHeaders] found its
     * token refreshed (a refresh's ID token replaces the stored user), or a fetched profile was stored.
     * [publishCurrentSession]'s guards keep it out of a session that logout or login has moved on, and [authState]
     * emits only when the user changed. Publishing also caches the current token. A failure to read the session keeps
     * the published user and the old cached token, since the headers are still valid; after a refresh, the next
     * [getAuthHeaders] call then sees the token as refreshed again and retries.
     *
     * @return false when the repository no longer has a session, which is then cleared.
     */
    private suspend fun republishSession(generation: Long): Boolean {
        val result = try {
            publishCurrentSession(generation, PublishSource.Authenticated)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return true
        }
        return when (result) {
            // A stale generation is caught by the caller's final generation check.
            PublishSessionResult.Published, PublishSessionResult.Stale -> true
            PublishSessionResult.NoSession -> {
                clearAuthenticatedIfGenerationCurrent(generation)
                false
            }
        }
    }

    private suspend fun isPublicationGuardAllowed(): Boolean =
        sessionPublicationGuard.canPublishStoredSession()
}

private enum class PublishSessionResult {
    Published,
    NoSession,
    Stale
}

private enum class CachedAccessToken {
    Unchanged,

    /** The token was refreshed since the session was last published. */
    Rotated,

    /** The repository has no access token, or the session moved on from the expected generation. */
    NotCurrent
}

private enum class PublishSource {
    Idle,
    ActiveLogin,
    Authenticated
}

private sealed interface AuthPublicationLifecycle {
    val generation: Long

    data class Idle(override val generation: Long) : AuthPublicationLifecycle
    data class Loading(
        override val generation: Long,
        val activeLogin: ActiveLogin
    ) : AuthPublicationLifecycle
    data class Authenticated(override val generation: Long) : AuthPublicationLifecycle
    data class Clearing(override val generation: Long) : AuthPublicationLifecycle
}

private fun AuthPublicationLifecycle.matchesPublishSource(
    expectedGeneration: Long,
    source: PublishSource
): Boolean =
    generation == expectedGeneration && when (source) {
        PublishSource.Idle -> this is AuthPublicationLifecycle.Idle
        PublishSource.ActiveLogin -> this is AuthPublicationLifecycle.Loading
        PublishSource.Authenticated -> this is AuthPublicationLifecycle.Authenticated
    }

private data class ActiveLogin(
    val completion: CompletableDeferred<Unit>,
    val generation: Long,
    val repositoryCommit: CompletableDeferred<Unit>,
    val repositoryJob: Job? = null
)

private data class LoginReservation(
    val completion: CompletableDeferred<Unit>,
    val generation: Long,
    val repositoryCommit: CompletableDeferred<Unit>
)

private data class ClearingReservation(
    val generation: Long
)

private class LoginInvalidatedException : IllegalStateException("Login was invalidated by a session lifecycle change")
