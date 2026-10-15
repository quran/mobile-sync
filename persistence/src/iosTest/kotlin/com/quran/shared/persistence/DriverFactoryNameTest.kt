package com.quran.shared.persistence

import co.touchlab.sqliter.DatabaseFileContext
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DriverFactoryNameTest {
    private val firstName = "driver_factory_name_${Random.nextInt().toUInt()}.db"
    private val secondName = "driver_factory_name_${Random.nextInt().toUInt()}.db"

    @AfterTest
    fun tearDown() {
        DatabaseFileContext.deleteDatabase(firstName)
        DatabaseFileContext.deleteDatabase(secondName)
    }

    @Test
    fun `databases with different names do not share data`() {
        val firstDriver = DriverFactory(firstName).makeDriver()
        val secondDriver = DriverFactory(secondName).makeDriver()
        try {
            MobileSyncDatabase(firstDriver).synced.collectionsQueries.collectionInsert("Study", "study", 0, 0, 0, 100L, 100L)

            val names = MobileSyncDatabase(secondDriver).synced.collectionsQueries.collectionsAll().executeAsList().map { it.name }
            assertEquals(listOf("Favorites"), names)
        } finally {
            firstDriver.close()
            secondDriver.close()
        }
    }
}
