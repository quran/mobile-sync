package com.quran.shared.pipeline.di

import com.quran.shared.auth.di.AuthModule
import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.model.AuthRuntimeConfig
import com.quran.shared.di.AppScope
import com.quran.shared.persistence.DriverFactory
import com.quran.shared.persistence.di.PersistenceModule
import com.quran.shared.pipeline.AppEnvironment
import com.quran.shared.pipeline.SyncAuthService
import com.quran.shared.pipeline.QuranDataService
import com.quran.shared.pipeline.QuranDataServiceModule
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
    bindingContainers = [
        PersistenceModule::class,
        AuthModule::class,
        MobileSyncStorageModule::class,
        QuranDataServiceModule::class
    ]
)
interface AppGraph {
    val quranDataService: QuranDataService
    val authService: SyncAuthService

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides driverFactory: DriverFactory,
            @Provides storage: MobileSyncStorage,
            @Provides environment: SynchronizationEnvironment,
            @Provides authRuntimeConfig: AuthRuntimeConfig
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
        authRuntimeConfig: AuthRuntimeConfig
    ): AppGraph {
        return createGraphFactory<AppGraph.Factory>()
            .create(driverFactory, storage, environment, authRuntimeConfig)
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

    /**
     * Initializes the managed graph using app-level OIDC client metadata when available.
     *
     * Blank [clientId] values are treated as an uncredentialed open-source build: the graph still
     * exposes [QuranDataService] for local-first data, while [SyncAuthService] reports authentication as
     * unavailable and sign-in fails clearly. Non-blank client IDs keep the configured OIDC path.
     */
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
            authRuntimeConfig = authRuntimeConfigForClientId(
                appEnvironment = appEnvironment,
                clientId = clientId,
                clientSecret = clientSecret
            )
        )
    }

    /**
     * Initializes the managed graph with explicit OIDC credentials.
     *
     * Use this overload when the app has already constructed a valid [AuthConfig]. Blank client
     * IDs still fail fast through [AuthConfig]; only the string-based convenience overloads
     * automatically fall back to local-only managed mode.
     */
    @OptIn(InternalCoroutinesApi::class)
    fun init(
        driverFactory: DriverFactory,
        storage: MobileSyncStorage,
        environment: SynchronizationEnvironment,
        authConfig: AuthConfig
    ): AppGraph =
        init(
            driverFactory = driverFactory,
            storage = storage,
            environment = environment,
            authRuntimeConfig = AuthRuntimeConfig.Configured(authConfig)
        )

    @OptIn(InternalCoroutinesApi::class)
    private fun init(
        driverFactory: DriverFactory,
        storage: MobileSyncStorage,
        environment: SynchronizationEnvironment,
        authRuntimeConfig: AuthRuntimeConfig
    ): AppGraph {
        return instance ?: synchronized(lock) {
            instance ?: doInit(driverFactory, storage, environment, authRuntimeConfig)
        }
    }
}

/**
 * Converts app-provided client metadata into the auth mode used by the managed graph.
 *
 * The convenience graph API accepts blank client IDs so open-source builds can boot without
 * credentials. The explicit [AuthConfig] path remains strict and should be used when callers want
 * invalid OIDC metadata to fail immediately.
 */
internal fun authRuntimeConfigForClientId(
    appEnvironment: AppEnvironment,
    clientId: String,
    clientSecret: String?
): AuthRuntimeConfig {
    val normalizedClientId = clientId.trim()
    return if (normalizedClientId.isEmpty()) {
        AuthRuntimeConfig.Unconfigured
    } else {
        AuthRuntimeConfig.Configured(
            AuthConfig(
                environment = appEnvironment.authEnvironment,
                clientId = normalizedClientId,
                clientSecret = clientSecret
            )
        )
    }
}
