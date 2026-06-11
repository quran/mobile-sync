package com.quran.shared.auth.service

import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.AuthRepositoryLoginCommitCallbacks
import com.quran.shared.auth.repository.LogoutTokenCaptureException
import com.quran.shared.auth.repository.LogoutTokenMaterial
import com.quran.shared.auth.repository.RemoteLogoutFailure
import com.quran.shared.di.AppScope
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
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
fun interface AuthSessionPublicationGuard {
    suspend fun canPublishStoredSession(): Boolean

    companion object {
        val Allow = AuthSessionPublicationGuard { true }
    }
}

@SingleIn(AppScope::class)
class AuthService @Inject constructor(
    private val authRepository: AuthRepository,
    private val sessionPublicationGuard: AuthSessionPublicationGuard = AuthSessionPublicationGuard.Allow
) {
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    private var cachedAccessToken: String? = null
    private val sessionPublicationMutex = Mutex()
    private var lifecycle: AuthPublicationLifecycle = AuthPublicationLifecycle.Idle(generation = 0)

    @NativeCoroutinesState
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

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

    @NativeCoroutines
    suspend fun login(): Unit = runLogin(LoginIntent.Normal)

    @NativeCoroutines
    suspend fun loginWithReauthentication(): Unit = runLogin(LoginIntent.Reauthenticate)

    private suspend fun runLogin(intent: LoginIntent) {
        val loginReservation = awaitDifferentIntentOrReserveLogin(intent) ?: return
        runActiveLogin(loginReservation) {
            runRepositoryLogin(intent, loginReservation)
        }
    }

    private suspend fun runRepositoryLogin(
        intent: LoginIntent,
        loginReservation: LoginReservation
    ) {
        val commitAwareRepository = authRepository as? AuthRepositoryLoginCommitCallbacks
        if (commitAwareRepository != null) {
            val onDurableCommit: suspend () -> Unit = {
                completeRepositoryCommitIfLoadingCurrent(loginReservation)
            }
            when (intent) {
                LoginIntent.Normal -> commitAwareRepository.login(onDurableCommit)
                LoginIntent.Reauthenticate -> commitAwareRepository.loginWithReauthentication(onDurableCommit)
            }
        } else {
            when (intent) {
                LoginIntent.Normal -> authRepository.login()
                LoginIntent.Reauthenticate -> authRepository.loginWithReauthentication()
            }
            completeRepositoryCommitIfLoadingCurrent(loginReservation)
        }
    }

    private suspend fun awaitDifferentIntentOrReserveLogin(intent: LoginIntent): LoginReservation? {
        while (true) {
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
                            intent = intent,
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
            if (existingLogin.intent == intent) {
                try {
                    existingLogin.completion.await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                }
                return null
            }
            try {
                existingLogin.completion.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
            } catch (e: LoginInvalidatedException) {
                throw e
            } catch (_: Exception) {
                // A different login intent still needs its own prompt after the previous attempt ends.
            }
        }
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
            withContext(NonCancellable) {
                val publishResult = if (repositoryLoginCompleted) {
                    tryPublishValidatedActiveLoginSession(loginReservation.generation)
                } else {
                    PublishSessionResult.NoSession
                }
                if (publishResult != PublishSessionResult.Published) {
                    failActiveLoginIfLoadingGenerationCurrent(e, "Login failed", loginReservation)
                }
                completeActiveLogin(loginReservation, e)
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

    @NativeCoroutines
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
                authRepository.attemptRemoteLogout(tokenMaterial)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Remote logout is best-effort after local session state is already unpublished.
            }
        }
    }

    suspend fun captureLogoutTokenMaterial(): LogoutTokenMaterial =
        authRepository.captureLogoutTokenMaterial()

    /**
     * Enters the non-publishable clearing lifecycle before draining active login work, then clears
     * persisted tokens. Lazy stored-session publication remains blocked until repository clear
     * has finished and the lifecycle settles back to idle.
     */
    suspend fun captureLogoutTokenMaterialForLogout(): LogoutTokenMaterial {
        return withClearing {
            authRepository.captureLogoutTokenMaterialAndClearLocalSession()
        }
    }

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

    suspend fun attemptRemoteLogout(tokenMaterial: LogoutTokenMaterial): List<RemoteLogoutFailure> =
        authRepository.attemptRemoteLogout(tokenMaterial)

    @NativeCoroutines
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

    private suspend fun refreshAuthenticatedSession(generation: Long): Boolean {
        if (!isPublicationGuardAllowed()) {
            clearAuthenticatedIfGenerationCurrent(generation)
            return false
        }
        val refreshed = try {
            authRepository.refreshTokensIfNeeded()
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

    @NativeCoroutines
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
        if (!cacheAccessTokenIfAuthenticated(generation)) {
            clearAuthenticatedIfGenerationCurrent(generation)
            return emptyMap()
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
                _authState.value = AuthState.Error(e, errorMessage)
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

    private suspend fun cacheAccessTokenIfAuthenticated(expectedGeneration: Long): Boolean {
        val accessToken = authRepository.getAccessToken()
        return sessionPublicationMutex.withLock {
            val current = lifecycle
            if (current is AuthPublicationLifecycle.Authenticated && current.generation == expectedGeneration) {
                if (accessToken == null) {
                    return@withLock false
                }
                cachedAccessToken = accessToken
                true
            } else {
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

private enum class LoginIntent {
    Normal,
    Reauthenticate
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
    val intent: LoginIntent,
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
