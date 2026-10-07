package com.quran.shared.persistence

import co.touchlab.sqliter.DatabaseFileContext
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

// The shared database is process-wide and stays open, so this is the only test that opens it.
class SharedDatabaseTest {
    @Test
    fun `failed shared open caches nothing and the next open is reused`() {
        val name = "shared_database_test.db"
        DatabaseFileContext.deleteDatabase(name)

        assertFailsWith<DatabaseStorageFullException> { openSharedDatabase(storageFullDriverFactory(name)) }
        openSharedDatabase(DriverFactory(name))

        // onConfiguration runs when the factory creates a driver, so any use of this factory fails.
        val unusable = DriverFactory(name) { error("opened again") }
        val database = makeDatabase(unusable)
        openSharedDatabase(unusable)
        assertSame(database, makeDatabase(unusable))
    }
}
