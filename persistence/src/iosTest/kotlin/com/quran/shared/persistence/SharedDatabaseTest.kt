package com.quran.shared.persistence

import co.touchlab.sqliter.DatabaseFileContext
import platform.Foundation.NSFileManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

// The shared database is process-wide, so only these tests open it, and each closes it when it ends.
class SharedDatabaseTest {
    private val name = "shared_database_test.db"

    @AfterTest
    fun tearDown() {
        deleteSharedDatabase()
        DatabaseFileContext.deleteDatabase(name)
    }

    @Test
    fun `failed shared open caches nothing and the next open is reused`() {
        DatabaseFileContext.deleteDatabase(name)

        assertFailsWith<DatabaseStorageFullException> { openSharedDatabase(storageFullDriverFactory(name)) }
        openSharedDatabase(DriverFactory(name))

        // onConfiguration runs when the factory creates a driver, so any use of this factory fails.
        val unusable = DriverFactory(name) { error("opened again") }
        val database = makeDatabase(unusable)
        openSharedDatabase(unusable)
        assertSame(database, makeDatabase(unusable))
    }

    @Test
    fun `deleting the shared database removes its file and the next open starts empty`() {
        DatabaseFileContext.deleteDatabase(name)
        makeDatabase(DriverFactory(name)).synced.collectionsQueries.collectionInsert("Study", "study", 0, 0, 0, 100L, 100L)
        val path = DatabaseFileContext.databasePath(name, null)
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(path))

        deleteSharedDatabase()

        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(path))
        val names = makeDatabase(DriverFactory(name)).synced.collectionsQueries.collectionsAll().executeAsList().map { it.name }
        assertEquals(listOf("Favorites"), names)
    }
}
