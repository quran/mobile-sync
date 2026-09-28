package com.quran.shared.persistence.repository

import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.repository.keyvalue.KeyValueRepositoryImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyValueRepositoryTest {
    private lateinit var database: QuranDatabase
    private lateinit var repository: KeyValueRepositoryImpl

    @BeforeTest
    fun setup() {
        database = QuranDatabase(TestDatabaseDriver().createDriver())
        repository = KeyValueRepositoryImpl(database)
    }

    @Test
    fun `put replaces existing value`() = runTest {
        repository.put("key", "first")
        repository.put("key", "second")

        assertEquals("second", repository.get("key"))
        assertEquals(setOf("key"), repository.keys())
    }

    @Test
    fun `remove deletes only the requested key`() = runTest {
        repository.put("kept", "1")
        repository.put("removed", "2")

        repository.remove("removed")

        assertNull(repository.get("removed"))
        assertEquals(setOf("kept"), repository.keys())
    }

    @Test
    fun `clear deletes all values`() = runTest {
        repository.put("a", "1")
        repository.put("b", "2")

        repository.clear()

        assertEquals(emptySet(), repository.keys())
    }

    @Test
    fun `values survive user data reset`() = runTest {
        repository.put("com.quran.sync.reset_in_progress", "true")

        PersistenceResetRepositoryImpl(database).deleteAllData()

        assertEquals("true", repository.get("com.quran.sync.reset_in_progress"))
    }
}
