package com.quran.shared.pipeline.di

import com.quran.shared.auth.di.AuthModule
import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.di.AppScope
import com.quran.shared.persistence.DriverFactory
import com.quran.shared.persistence.di.PersistenceModule
import com.quran.shared.pipeline.AppEnvironment
import com.quran.shared.pipeline.SyncAuthService
import com.quran.shared.pipeline.SyncService
import com.quran.shared.pipeline.defaultAppEnvironment
import com.quran.shared.pipeline.storage.MobileSyncStorage
import com.quran.shared.pipeline.storage.MobileSyncStorageModule
import com.quran.shared.syncengine.SynchronizationEnvironment
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import kotlin.concurrent.Volatile

/**
 * Central dependency graph for the application.
 *
 * Note: [bindingContainers] is used explicitly instead of relying on Metro's automated
 * @ContributesTo aggregation because automated aggregation does not work reliably on
 * native/iOS targets due to a known Metro/Kotlin compiler limitation (KT-75865).
 * When this is fixed upstream, the modules can be removed from here and aggregation
 * will be handled automatically via @ContributesTo annotations on each module.
 */
@DependencyGraph(
    AppScope::class,
    bindingContainers = [PersistenceModule::class, AuthModule::class, MobileSyncStorageModule::class]
)
interface AppGraph {
    val syncService: SyncService
    val authService: SyncAuthService

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides driverFactory: DriverFactory,
            @Provides storage: MobileSyncStorage,
            @Provides environment: SynchronizationEnvironment,
            @Provides authConfig: AuthConfig
        ): AppGraph
    }
}

object SharedDependencyGraph {
    @Volatile
    private var instance: AppGraph? = null
    @OptIn(InternalCoroutinesApi::class)
    private val lock = SynchronizedObject()

    private fun doInit(
        driverFactory: DriverFactory,
        storage: MobileSyncStorage,
        environment: SynchronizationEnvironment,
        authConfig: AuthConfig
    ): AppGraph {
        return createGraphFactory<AppGraph.Factory>()
            .create(driverFactory, storage, environment, authConfig)
            .also { instance = it }
    }

    @OptIn(InternalCoroutinesApi::class)
    fun init(
        driverFactory: DriverFactory,
        storage: MobileSyncStorage,
        clientId: String,
        clientSecret: String? = null
    ): AppGraph {
        return init(
            driverFactory = driverFactory,
            storage = storage,
            appEnvironment = defaultAppEnvironment(),
            clientId = clientId,
            clientSecret = clientSecret
        )
    }

    @OptIn(InternalCoroutinesApi::class)
    fun init(
        driverFactory: DriverFactory,
        storage: MobileSyncStorage,
        appEnvironment: AppEnvironment,
        clientId: String,
        clientSecret: String? = null
    ): AppGraph {
        return init(
            driverFactory = driverFactory,
            storage = storage,
            environment = appEnvironment.synchronizationEnvironment(),
            authConfig = AuthConfig(
                environment = appEnvironment.authEnvironment,
                clientId = clientId,
                clientSecret = clientSecret
            )
        )
    }

    @OptIn(InternalCoroutinesApi::class)
    fun init(
        driverFactory: DriverFactory,
        storage: MobileSyncStorage,
        environment: SynchronizationEnvironment,
        authConfig: AuthConfig
    ): AppGraph {
        return instance ?: synchronized(lock) {
            instance ?: doInit(driverFactory, storage, environment, authConfig)
        }
    }
}
