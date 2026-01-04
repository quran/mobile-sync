package com.quran.shared.persistence.repository.collection

import com.quran.shared.persistence.DriverFactory
import com.quran.shared.persistence.makeDatabase
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepository
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collection.repository.CollectionsSynchronizationRepository

/**
 * Factory for creating CollectionsRepository instances.
 * This factory hides the details of database creation and provides a clean interface
 * for obtaining repository instances.
 */
object CollectionsRepositoryFactory {
    /**
     * Creates a new instance of CollectionsRepository.
     *
     * @param driverFactory The driver factory to use for database creation
     * @return CollectionsRepository A new repository instance
     */
    fun createRepository(driverFactory: DriverFactory): CollectionsRepository {
        val database = makeDatabase(driverFactory)
        return CollectionsRepositoryImpl(database)
    }

    /**
     * Creates a new instance of CollectionsSynchronizationRepository.
     *
     * @param driverFactory The driver factory to use for database creation
     * @return CollectionsSynchronizationRepository A new synchronization repository instance
     */
    fun createSynchronizationRepository(driverFactory: DriverFactory): CollectionsSynchronizationRepository {
        val database = makeDatabase(driverFactory)
        return CollectionsRepositoryImpl(database)
    }
}
