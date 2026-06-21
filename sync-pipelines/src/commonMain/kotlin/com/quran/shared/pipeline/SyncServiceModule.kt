package com.quran.shared.pipeline

import com.quran.shared.di.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import kotlin.native.HiddenFromObjC

/**
 * Metro bindings owned by the sync service layer.
 */
@ContributesTo(AppScope::class)
@BindingContainer
@HiddenFromObjC
abstract class SyncServiceModule {
    /**
     * Provides the production synchronization client factory used by [SyncService].
     */
    @Binds
    internal abstract fun bindSyncServiceSynchronizationClientFactory(
        impl: DefaultSyncServiceSynchronizationClientFactory
    ): SyncServiceSynchronizationClientFactory
}
