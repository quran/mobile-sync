package com.quran.shared.pipeline

import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.model.AuthRuntimeConfig
import com.quran.shared.auth.model.asAuthenticationException
import com.quran.shared.auth.service.AuthService
import com.quran.shared.di.AppScope
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * Managed sync-graph authentication facade.
 *
 * Login state is delegated to the auth module, while logout is routed through [QuranDataService] so
 * managed applications cannot bypass reset, sync drain, local data clear, and token reset work.
 */
@Inject
@SingleIn(AppScope::class)
class SyncAuthService internal constructor(
    private val authService: AuthService,
    private val quranDataService: QuranDataService,
    private val sessionLifecycleCoordinator: SessionLifecycleCoordinator,
    private val authRuntimeConfig: AuthRuntimeConfig = AuthRuntimeConfig.Unconfigured
) {
    @NativeCoroutinesState
    val authState: StateFlow<AuthState> get() = authService.authState

    /**
     * True when this graph was initialized with usable authentication client metadata.
     *
     * When false, [QuranDataService] still supports local-first data operations, but interactive sign-in
     * is not available for this build.
     */
    val isAuthenticationConfigured: Boolean
        get() = authRuntimeConfig.isConfigured

    /**
     * Signs in through the browser and publishes the session, which starts a sync. The identity provider always
     * asks for credentials again (`prompt=login`), so a sign-in after a logout never reuses its browser session.
     *
     * Local writes and imports don't wait for it, however long the user takes. A logout during it ends
     * the sign-in: the call that opened the browser throws [CancellationException], and a call made
     * while it ran, which waits on it, throws
     * [AuthenticationFailedException][com.quran.shared.auth.model.AuthenticationFailedException].
     * Started while a logout is running, it fails with [SessionResetInProgressException].
     */
    @NativeCoroutines
    suspend fun login() {
        quranDataService.awaitInitialization()
        sessionLifecycleCoordinator.withLogin {
            authService.login()
        }
    }

    @NativeCoroutines
    suspend fun refreshAuthentication(): Boolean = withAuthenticationErrors {
        quranDataService.awaitInitialization()
        authService.refreshAccessTokenIfNeeded()
    }

    @NativeCoroutines
    suspend fun authenticationHeaders(): Map<String, String> = withAuthenticationErrors {
        quranDataService.awaitInitialization()
        authService.getAuthHeaders()
    }

    @NativeCoroutines
    suspend fun logout(clearLocalData: Boolean = true): LogoutResult = withAuthenticationErrors {
        quranDataService.logout(clearLocalData)
    }

    fun clearError() {
        authService.clearError()
    }

    fun isLoggedIn(): Boolean = authService.isLoggedIn()

    /** Cancels and waits for the auth service's background work. */
    internal suspend fun clearAndJoin() {
        authService.clearAndJoin()
    }

    private suspend fun <T> withAuthenticationErrors(operation: suspend () -> T): T =
        try {
            operation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e.asAuthenticationException()
        }
}
