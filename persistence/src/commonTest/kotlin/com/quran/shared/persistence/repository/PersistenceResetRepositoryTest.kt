package com.quran.shared.persistence.repository

import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersistenceResetRepositoryTest {
    @Test
    fun `reset restores seeded system collections`() {
        val database = QuranDatabase(TestDatabaseDriver().createDriver())
        database.collectionsQueries.addNewCollection(
            name = "Study",
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
