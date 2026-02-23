package com.quran.shared.pipeline.di

import com.quran.shared.auth.di.AuthModule
import com.quran.shared.auth.service.AuthService
import com.quran.shared.di.AppScope
import com.quran.shared.persistence.DriverFactory
import com.quran.shared.persistence.di.PersistenceModule
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepository
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepository
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepository
import com.quran.shared.persistence.repository.note.repository.NotesRepository
import com.quran.shared.persistence.repository.recentpage.repository.RecentPagesRepository
import com.quran.shared.pipeline.SyncService
import com.quran.shared.syncengine.SynchronizationEnvironment
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory

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
    val notesRepository: NotesRepository
    val recentPagesRepository: RecentPagesRepository

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides driverFactory: DriverFactory,
            @Provides environment: SynchronizationEnvironment
        ): AppGraph
    }
}

object SharedDependencyGraph {
    private var instance: AppGraph? = null

    fun init(driverFactory: DriverFactory, environment: SynchronizationEnvironment): AppGraph {
        return createGraphFactory<AppGraph.Factory>().create(driverFactory, environment).also {
            instance = it
        }
    }

    fun get(): AppGraph = instance ?: error("Graph not initialized. Call SharedDependencyGraph.init() first.")
}
