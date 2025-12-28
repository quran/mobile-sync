package com.quran.shared.persistence.repository.bookmark

import com.quran.shared.persistence.DriverFactory
import com.quran.shared.persistence.makeDatabase
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepository
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepositoryImpl
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksSynchronizationRepository

/**
 * Factory for creating BookmarksRepository instances.
 * This factory hides the details of database creation and provides a clean interface
 * for obtaining repository instances.
 */
object BookmarksRepositoryFactory {
    /**
     * Creates a new instance of BookmarksRepository.
     * The repository is backed by a SQLite database created using the provided driver factory.
     *
     * @param driverFactory The driver factory to use for database creation
     * @return BookmarksRepository A new repository instance
     */
    fun createRepository(driverFactory: DriverFactory): BookmarksRepository {
        val database = makeDatabase(driverFactory)
        return BookmarksRepositoryImpl(database)
    }

    /**
     * Creates a new instance of BookmarksSynchronizationRepository.
     * This repository provides synchronization-specific operations for bookmarks.
     *
     * @param driverFactory The driver factory to use for database creation
     * @return BookmarksSynchronizationRepository A new synchronization repository instance
     */
    fun createSynchronizationRepository(driverFactory: DriverFactory): BookmarksSynchronizationRepository {
        val database = makeDatabase(driverFactory)
        return BookmarksRepositoryImpl(database)
    }
}