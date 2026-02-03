package com.quran.shared.pipeline

import com.quran.shared.auth.di.AuthConfigFactory
import com.quran.shared.auth.service.AuthService
import com.quran.shared.persistence.DriverFactory
import com.quran.shared.persistence.repository.bookmark.BookmarksRepositoryFactory
import com.quran.shared.persistence.repository.collection.CollectionsRepositoryFactory
import com.quran.shared.persistence.repository.collectionbookmark.CollectionBookmarksRepositoryFactory
import com.quran.shared.persistence.repository.note.NotesRepositoryFactory
import com.quran.shared.syncengine.SynchronizationEnvironment

/**
 * Factory for creating synchronization-related components.
 */
object SyncPipelineFactory {

    /**
     * Creates a [SyncService] instance with a default pipeline configuration.
     * 
     * @param driverFactory Platform-specific driver factory for database access.
     * @param environment The synchronization environment (URL, etc).
     * @param authService The authentication service (defaults to the one from AuthConfigFactory).
     */
    fun createSyncService(
        driverFactory: DriverFactory,
        environment: SynchronizationEnvironment,
        authService: AuthService = AuthConfigFactory.authService
    ): SyncService {

        val pipeline = SyncEnginePipeline(
            bookmarksRepository = BookmarksRepositoryFactory.createSynchronizationRepository(driverFactory),
            collectionsRepository = CollectionsRepositoryFactory.createSynchronizationRepository(driverFactory),
            collectionBookmarksRepository = CollectionBookmarksRepositoryFactory.createSynchronizationRepository(driverFactory),
            notesRepository = NotesRepositoryFactory.createSynchronizationRepository(driverFactory)
        )
        
        return SyncService(
            authService = authService,
            pipeline = pipeline,
            environment = environment
        )
    }

    /**
     * for usage from Swift Code
     */
    fun createSyncService(
        driverFactory: DriverFactory,
        environment: SynchronizationEnvironment,
    ): SyncService  = createSyncService(driverFactory, environment, AuthConfigFactory.authService)


    /**
     * Creates a [SyncViewModel] instance using a provided [SyncService].
     * 
     * @param service The [SyncService] to wrap.
     * @param authService The authentication service (defaults to the one from AuthConfigFactory).
     */
    fun createSyncViewModel(
        service: SyncService,
        authService: AuthService = AuthConfigFactory.authService
    ): SyncViewModel {
        return SyncViewModel(
            authService = authService,
            service = service
        )
    }

    /**
     * Creates a [SyncViewModel] instance, handling the creation of the underlying [SyncService].
     * 
     * @param driverFactory Platform-specific driver factory for database access.
     * @param environment The synchronization environment (URL, etc).
     * @param authService The authentication service (defaults to the one from AuthConfigFactory).
     */
    fun createSyncViewModel(
        driverFactory: DriverFactory,
        environment: SynchronizationEnvironment,
        authService: AuthService = AuthConfigFactory.authService
    ): SyncViewModel {
        val service = createSyncService(driverFactory, environment, authService)
        return createSyncViewModel(service, authService)
    }
}
