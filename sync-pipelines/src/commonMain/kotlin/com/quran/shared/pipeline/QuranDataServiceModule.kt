package com.quran.shared.pipeline

import com.quran.shared.di.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import kotlin.native.HiddenFromObjC

/**
 * Metro bindings owned by the managed Quran data service layer.
 */
@ContributesTo(AppScope::class)
@BindingContainer
@HiddenFromObjC
abstract class QuranDataServiceModule {
    /**
     * Provides the production synchronization client factory used by [QuranDataService].
     */
    @Binds
    internal abstract fun bindQuranDataServiceSynchronizationClientFactory(
        impl: DefaultQuranDataServiceSynchronizationClientFactory
    ): QuranDataServiceSynchronizationClientFactory
}
