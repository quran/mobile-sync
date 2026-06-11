package com.quran.shared.pipeline

import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.auth.service.AuthService
import com.quran.shared.di.AppScope
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.StateFlow

/**
 * Managed sync-graph authentication facade.
 *
 * Login state is delegated to the auth module, while logout is routed through [SyncService] so
 * managed applications cannot bypass reset, sync drain, local data clear, and token reset work.
 */
@Inject
@SingleIn(AppScope::class)
class SyncAuthService internal constructor(
    private val authService: AuthService,
    private val syncService: SyncService,
    private val sessionLifecycleCoordinator: SessionLifecycleCoordinator
) {
    @NativeCoroutinesState
    val authState: StateFlow<AuthState> get() = authService.authState

    val loggedInUser: UserInfo?
        get() = (authService.authState.value as? AuthState.Success)?.userInfo

    @NativeCoroutines
    suspend fun login() {
        sessionLifecycleCoordinator.withMutatingWrite {
            authService.login()
        }
    }

    @NativeCoroutines
    suspend fun loginWithReauthentication() {
        sessionLifecycleCoordinator.withMutatingWrite {
            authService.loginWithReauthentication()
        }
    }

    @NativeCoroutines
    suspend fun signInWithReauthentication() {
        loginWithReauthentication()
    }

    @NativeCoroutines
    suspend fun refreshAuthentication(): Boolean =
        authService.refreshAccessTokenIfNeeded()

    @NativeCoroutines
    suspend fun authenticationHeaders(): Map<String, String> =
        authService.getAuthHeaders()

    @NativeCoroutines
    suspend fun logout(clearLocalData: Boolean = true): LogoutResult =
        syncService.logout(clearLocalData)

    fun clearError() {
        authService.clearError()
    }

    fun isLoggedIn(): Boolean = authService.isLoggedIn()
}
