package com.quran.shared.auth.di

import org.publicvalue.multiplatform.oidc.appsupport.CodeAuthFlowFactory

/**
 * Platform-specific provider for CodeAuthFlowFactory.
 * 
 * On Android, call `AuthFlowFactoryProvider.initialize(factory)` in your Activity's onCreate(),
 * passing an `AndroidCodeAuthFlowFactory` that has been registered with the activity.
 * 
 * On iOS, call `AuthFlowFactoryProvider.initialize(IosCodeAuthFlowFactory())` during app setup.
 */
object AuthFlowFactoryProvider {
    private var _factory: CodeAuthFlowFactory? = null
    
    /**
     * The CodeAuthFlowFactory instance. Must be initialized before use.
     */
    val factory: CodeAuthFlowFactory
        get() = _factory ?: throw IllegalStateException(
            "CodeAuthFlowFactory not initialized. Call AuthFlowFactoryProvider.initialize() first."
        )
    
    /**
     * Initialize the factory. Should be called from platform-specific code:
     * - Android: Call in Activity.onCreate() with AndroidCodeAuthFlowFactory
     * - iOS: Call during app initialization with IosCodeAuthFlowFactory
     */
    fun initialize(factory: CodeAuthFlowFactory) {
        _factory = factory
    }
    
    /**
     * Check if the factory has been initialized.
     */
    fun isInitialized(): Boolean = _factory != null
}
