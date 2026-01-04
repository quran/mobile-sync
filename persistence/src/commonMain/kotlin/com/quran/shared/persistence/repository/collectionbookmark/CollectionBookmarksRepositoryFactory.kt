package com.quran.shared.persistence.repository.collectionbookmark

import com.quran.shared.persistence.DriverFactory
import com.quran.shared.persistence.makeDatabase
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepository
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksSynchronizationRepository

/**
 * Factory for creating CollectionBookmarksRepository instances.
 */
object CollectionBookmarksRepositoryFactory {
    /**
     * Creates a new instance of CollectionBookmarksRepository.
     */
    fun createRepository(driverFactory: DriverFactory): CollectionBookmarksRepository {
        val database = makeDatabase(driverFactory)
        return CollectionBookmarksRepositoryImpl(database)
    }

    /**
     * Creates a new instance of CollectionBookmarksSynchronizationRepository.
     */
    fun createSynchronizationRepository(driverFactory: DriverFactory): CollectionBookmarksSynchronizationRepository {
        val database = makeDatabase(driverFactory)
        return CollectionBookmarksRepositoryImpl(database)
    }
}
