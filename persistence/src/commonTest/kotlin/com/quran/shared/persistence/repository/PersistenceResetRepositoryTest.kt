package com.quran.shared.persistence.repository

import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.createQuranDatabase
import com.quran.shared.persistence.model.CollectionName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersistenceResetRepositoryTest {
    @Test
    fun `reset restores seeded system collections`() {
        val database = createQuranDatabase(TestDatabaseDriver().createDriver())
        database.collectionsQueries.addNewCollection(
            name = CollectionName.of("Study"),
            name_key = CollectionName.of("Study").key,
            timestamp = 100L,
            is_system = 0L
        )

        PersistenceResetRepositoryImpl(database).deleteAllData()

        val collections = database.collectionsQueries.getCollections().executeAsList()
        assertEquals(6, collections.size)
        assertEquals(1, collections.count { it.is_default == 1L })
        assertTrue(collections.all { it.is_system == 1L })
    }
}
