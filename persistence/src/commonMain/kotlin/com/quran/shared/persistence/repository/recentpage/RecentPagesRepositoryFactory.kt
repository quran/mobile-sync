package com.quran.shared.persistence.repository.recentpage

import com.quran.shared.persistence.DriverFactory
import com.quran.shared.persistence.makeDatabase
import com.quran.shared.persistence.repository.recentpage.repository.RecentPagesRepository
import com.quran.shared.persistence.repository.recentpage.repository.RecentPagesRepositoryImpl

/**
 * Factory for creating RecentPagesRepository instances.
 * This factory hides the details of database creation and provides a clean interface
 * for obtaining repository instances.
 */
object RecentPagesRepositoryFactory {
    /**
     * Creates a new instance of RecentPagesRepository.
     * The repository is backed by a SQLite database created using the provided driver factory.
     *
     * @param driverFactory The driver factory to use for database creation
     * @return RecentPagesRepository A new repository instance
     */
    fun createRepository(driverFactory: DriverFactory): RecentPagesRepository {
        val database = makeDatabase(driverFactory)
        return RecentPagesRepositoryImpl(database)
    }
}
