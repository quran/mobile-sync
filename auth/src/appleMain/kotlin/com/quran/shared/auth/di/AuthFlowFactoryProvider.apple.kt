package com.quran.shared.auth.di

import org.publicvalue.multiplatform.oidc.appsupport.IosCodeAuthFlowFactory

/**
 * Extension to initialize the factory on iOS/Apple platforms.
 * Can be called directly from Swift.
 */
fun AuthFlowFactoryProvider.doInitialize() {
    this.initialize(IosCodeAuthFlowFactory())
}
