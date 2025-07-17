package com.quran.shared.persistence.repository

import com.quran.shared.persistence.DriverFactory
import com.quran.shared.persistence.makeDatabase

/**
 * Factory for creating PageBookmarksRepository instances.
 * This factory hides the details of database creation and provides a clean interface
 * for obtaining repository instances.
 */
class PageBookmarksRepositoryFactory {
    companion object {
        /**
         * Creates a new instance of PageBookmarksRepository.
         * The repository is backed by a SQLite database created using the provided driver factory.
         *
         * @param driverFactory The driver factory to use for database creation
         * @return PageBookmarksRepository A new repository instance
         */
        fun createRepository(driverFactory: DriverFactory): PageBookmarksRepository {
            val database = makeDatabase(driverFactory)
            return PageBookmarksRepositoryImpl(database)
        }

        /**
         * Creates a new instance of PageBookmarksSynchronizationRepository.
         * This repository provides synchronization-specific operations for page bookmarks.
         *
         * @param driverFactory The driver factory to use for database creation
         * @return PageBookmarksSynchronizationRepository A new synchronization repository instance
         */
        fun createSynchronizationRepository(driverFactory: DriverFactory): PageBookmarksSynchronizationRepository {
            val database = makeDatabase(driverFactory)
            return PageBookmarksRepositoryImpl(database)
        }
    }
} 