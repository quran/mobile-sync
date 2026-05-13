package com.quran.shared.auth.service

import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.OidcAuthRepository
import com.quran.shared.di.AppScope
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Shared service for authentication logic and state.
 *
 * This service centralizes authentication operations and maintains the authentication state,
 * making it easy to share across platforms while allowing native ViewModels (iOS/Android)
 * to handle platform-specific UI concerns.
 */
@SingleIn(AppScope::class)
class AuthService @Inject constructor(
    private val authRepository: AuthRepository
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    private var cachedAccessToken: String? = null

    @NativeCoroutinesState
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        scope.launch {
            checkCurrentSession()
            checkPendingLogin()
        }
    }

    private suspend fun checkCurrentSession() {
        if (!publishCurrentSession()) {
            cachedAccessToken = null
            _authState.value = AuthState.Idle
        }
    }

    private suspend fun checkPendingLogin() {
        try {
            val oidcRepo = authRepository as? OidcAuthRepository
            if (oidcRepo?.canContinueLogin() == true) {
                _authState.value = AuthState.Loading
                oidcRepo.continueLogin()

                publishCurrentSession()
            }
        } catch (e: Exception) {
            // Ignore - no pending login
        }
    }


    @NativeCoroutines
    suspend fun login(): Unit {
        try {
            _authState.value = AuthState.Loading
            authRepository.login()
            if (!publishCurrentSession()) {
                throw Exception("Failed to persist authentication tokens after login")
            }
        } catch (e: Exception) {
            handleError(e, "Login failed")
            throw e
        }
    }

    @NativeCoroutines
    suspend fun loginWithReauthentication(): Unit {
        try {
            _authState.value = AuthState.Loading
            authRepository.loginWithReauthentication()
            if (!publishCurrentSession()) {
                throw Exception("Failed to persist authentication tokens after login")
            }
        } catch (e: Exception) {
            handleError(e, "Login failed")
            throw e
        }
    }

    @NativeCoroutines
    suspend fun logout(): Unit {
        try {
            authRepository.logout()
            cachedAccessToken = null
            _authState.value = AuthState.Idle
        } catch (e: Exception) {
            handleError(e, "Logout failed")
            throw e
        }
    }

    @NativeCoroutines
    suspend fun refreshAccessTokenIfNeeded(): Boolean {
        val refreshed = authRepository.refreshTokensIfNeeded()
        if (refreshed) {
            publishCurrentSession()
        } else {
            cachedAccessToken = null
            _authState.value = AuthState.Idle
        }
        return refreshed
    }

    fun isLoggedIn(): Boolean = _authState.value is AuthState.Success

    fun getAccessToken(): String? = cachedAccessToken

    @NativeCoroutines
    suspend fun getAuthHeaders(): Map<String, String> {
        val headers = authRepository.getAuthHeaders()
        if (headers.isEmpty()) {
            cachedAccessToken = null
            _authState.value = AuthState.Idle
        } else {
            cachedAccessToken = authRepository.getAccessToken()
        }
        return headers
    }

    /**
     * Publishes an authenticated session when token material is available, regardless of whether
     * profile metadata is currently cached or fetchable.
     */
    private suspend fun publishCurrentSession(): Boolean {
        if (!authRepository.isLoggedIn()) {
            return false
        }

        cachedAccessToken = authRepository.getAccessToken()
        if (cachedAccessToken == null) {
            return false
        }

        _authState.value = AuthState.Success(authRepository.getCurrentUser())
        return true
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
}
