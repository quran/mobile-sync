package com.quran.shared.auth.di

import com.quran.shared.auth.BuildKonfig
import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.persistence.AuthStorage
import com.quran.shared.auth.repository.AuthRepository
import com.quran.shared.auth.repository.AuthNetworkDataSource
import com.quran.shared.auth.repository.OidcAuthRepository

/**
 * Manual dependency injection for auth module.
 * 
 * For the OAuth flow to work, you must initialize the CodeAuthFlowFactory:
 * - On Android: Call AuthFlowFactoryProvider.initialize() with AndroidCodeAuthFlowFactory in MainActivity
 * - On iOS: Call AuthFlowFactoryProvider.doInitialize() with IosCodeAuthFlowFactory at app startup
 */
object AuthConfigFactory {
    fun createDefault(): AuthConfig {
        return AuthConfig(
            usePreProduction = true, // Set based on Build Flavor or Toggle
            clientId = BuildKonfig.CLIENT_ID,
            clientSecret = BuildKonfig.CLIENT_SECRET
        )
    }

    // Singletons
    val authStorage: AuthStorage by lazy { AuthStorage() }
    
    val authNetworkDataSource: AuthNetworkDataSource by lazy {
        AuthNetworkDataSource(createDefault())
    }
    
    val authRepository: AuthRepository by lazy { 
        OidcAuthRepository(createDefault(), authStorage, authNetworkDataSource) 
    }
}
