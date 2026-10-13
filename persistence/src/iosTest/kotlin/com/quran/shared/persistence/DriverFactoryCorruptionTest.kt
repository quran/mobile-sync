@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.quran.shared.persistence

import co.touchlab.sqliter.DatabaseFileContext
import com.quran.shared.persistence.model.CollectionName
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.chmod

class DriverFactoryCorruptionTest {
    private val name = "driver_factory_${Random.nextInt().toUInt()}.db"
    private val path = DatabaseFileContext.databasePath(name, null)

    @AfterTest
    fun tearDown() {
        chmod(path, (S_IRUSR or S_IWUSR).toUShort())
        DatabaseFileContext.deleteDatabase(name)
    }

    @Test
    fun `file that is not a database is replaced with a fresh database`() {
        writeNonDatabaseFile()

        val driver = DriverFactory(name).makeDriver()
        try {
            val collections = createQuranDatabase(driver).collectionsQueries.getCollections().executeAsList()
            assertEquals(6, collections.size)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `recreating a corrupt database on a full disk throws storage full exception`() {
        writeNonDatabaseFile()

        assertFailsWith<DatabaseStorageFullException> { storageFullDriverFactory(name).makeDriver() }

        assertNotEquals(NON_DATABASE_CONTENTS, NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null))
    }

    @Test
    fun `unreadable database file is kept`() {
        writeNonDatabaseFile()
        chmod(path, 0u)

        assertFails { DriverFactory(name).makeDriver() }

        chmod(path, (S_IRUSR or S_IWUSR).toUShort())
        assertEquals(NON_DATABASE_CONTENTS, NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null))
    }

    @Test
    fun `healthy database keeps its data`() {
        val firstDriver = DriverFactory(name).makeDriver()
        createQuranDatabase(firstDriver).collectionsQueries.addNewCollection(
            name = CollectionName.of("Study"),
            name_key = CollectionName.of("Study").key,
            timestamp = 100L,
            is_system = 0L
        )
        firstDriver.close()

        val driver = DriverFactory(name).makeDriver()
        try {
            val names = createQuranDatabase(driver).collectionsQueries.getCollections().executeAsList().map { it.name.value }
            assertTrue("Study" in names)
        } finally {
            driver.close()
        }
    }

    private fun writeNonDatabaseFile() {
        val written = NSString.create(string = NON_DATABASE_CONTENTS)
            .writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        assertTrue(written)
    }

    private companion object {
        val NON_DATABASE_CONTENTS = "not a database ".repeat(512)
    }
}
