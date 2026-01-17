package com.quran.shared.auth.di

import com.quran.shared.auth.AuthenticationManager
import com.quran.shared.auth.BuildKonfig
import com.quran.shared.auth.model.AuthConfig

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
}

// Instantiate the manager
val authManager = AuthenticationManager(AuthConfigFactory.createDefault())