package com.quran.shared.auth.di

import com.quran.shared.auth.AuthenticationManager
import com.quran.shared.auth.BuildKonfig
import com.quran.shared.auth.model.AuthConfig

import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.AuthRepositoryImpl

/**
 * manual dependency injection for auth, could be replaced by DI framework like koin
 */
object AuthConfigFactory {
    fun createDefault(): AuthConfig {
        return AuthConfig(
            usePreProduction = true, // Set based on Build Flavor or Toggle
            clientId = BuildKonfig.CLIENT_ID,
            clientSecret = null
        )
    }

    // Singletons
    val authStorage: AuthStorage by lazy { AuthStorage() }
    val authManager: AuthenticationManager by lazy { AuthenticationManager(createDefault()) }
    val authRepository: AuthRepository by lazy { AuthRepositoryImpl(authManager, authStorage) }
}