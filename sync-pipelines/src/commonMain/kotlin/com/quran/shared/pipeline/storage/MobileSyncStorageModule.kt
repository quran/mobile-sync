package com.quran.shared.pipeline.storage

import com.quran.shared.di.AppScope
import com.quran.shared.pipeline.SyncLocalModificationDateStore
import com.quran.shared.pipeline.SyncSettingsLocalModificationDateStore
import com.russhwolf.settings.coroutines.SuspendSettings
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import org.publicvalue.multiplatform.oidc.tokenstore.TokenStore

@ContributesTo(AppScope::class)
@BindingContainer
abstract class MobileSyncStorageModule {

    @Binds
    abstract fun bindSyncLocalModificationDateStore(
        impl: SyncSettingsLocalModificationDateStore
    ): SyncLocalModificationDateStore

    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun provideTokenStore(storage: MobileSyncStorage): TokenStore {
            return storage.tokenStore
        }

        @Provides
        @SingleIn(AppScope::class)
        fun provideSettings(storage: MobileSyncStorage): SuspendSettings {
            return storage.settings
        }
    }
}
