package com.quran.shared.pipeline.di

import com.quran.shared.auth.di.AuthModule
import com.quran.shared.auth.model.AuthConfig
import com.quran.shared.auth.service.AuthService
import com.quran.shared.di.AppScope
import com.quran.shared.persistence.DriverFactory
import com.quran.shared.persistence.di.PersistenceModule
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepository
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepository
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepository
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepository
import com.quran.shared.persistence.repository.note.repository.NotesRepository
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepository
import com.quran.shared.pipeline.AppEnvironment
import com.quran.shared.pipeline.SyncService
import com.quran.shared.pipeline.defaultAppEnvironment
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
    bindingContainers = [PersistenceModule::class, AuthModule::class]
)
interface AppGraph {
    val syncService: SyncService
    val authService: AuthService
    val bookmarksRepository: BookmarksRepository
    val collectionsRepository: CollectionsRepository
    val collectionBookmarksRepository: CollectionBookmarksRepository
    val persistenceImportRepository: PersistenceImportRepository
    val notesRepository: NotesRepository
    val readingSessionsRepository: ReadingSessionsRepository

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides driverFactory: DriverFactory,
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
        environment: SynchronizationEnvironment,
        authConfig: AuthConfig
    ): AppGraph {
        return createGraphFactory<AppGraph.Factory>()
            .create(driverFactory, environment, authConfig)
            .also { instance = it }
    }

    @OptIn(InternalCoroutinesApi::class)
    fun init(
        driverFactory: DriverFactory,
        clientId: String,
        clientSecret: String? = null
    ): AppGraph {
        return init(
            driverFactory = driverFactory,
            appEnvironment = defaultAppEnvironment(),
            clientId = clientId,
            clientSecret = clientSecret
        )
    }

    @OptIn(InternalCoroutinesApi::class)
    fun init(
        driverFactory: DriverFactory,
        appEnvironment: AppEnvironment,
        clientId: String,
        clientSecret: String? = null
    ): AppGraph {
        return init(
            driverFactory = driverFactory,
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
        environment: SynchronizationEnvironment,
        authConfig: AuthConfig
    ): AppGraph {
        return instance ?: synchronized(lock) {
            instance ?: doInit(driverFactory, environment, authConfig)
        }
    }
}
