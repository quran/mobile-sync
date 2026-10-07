package com.quran.shared.persistence

import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.DatabaseFileContext
import co.touchlab.sqliter.NO_VERSION_CHECK
import co.touchlab.sqliter.createDatabaseManager
import co.touchlab.sqliter.interop.SQLiteExceptionErrorCode
import co.touchlab.sqliter.interop.SqliteErrorType
import co.touchlab.sqliter.longForQuery
import co.touchlab.sqliter.stringForQuery
import co.touchlab.sqliter.withConnection
import co.touchlab.sqliter.withStatement
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIsNot
import kotlin.test.assertTrue

class DriverFactoryStorageFullTest {
    private val name = "storage_full_${Random.nextInt().toUInt()}.db"

    @AfterTest
    fun tearDown() {
        DatabaseFileContext.deleteDatabase(name)
    }

    @Test
    fun `full disk throws storage full exception wrapping the sqlite error`() {
        val error = assertFailsWith<DatabaseStorageFullException> {
            storageFullDriverFactory(name).makeDriver()
        }

        val sqliteErrors = generateSequence(error.cause) { it.cause }.filterIsInstance<SQLiteExceptionErrorCode>()
        assertTrue(sqliteErrors.any { it.errorType == SqliteErrorType.SQLITE_FULL })
    }

    @Test
    fun `full disk keeps the existing database file`() {
        writeUnversionedDatabase(marker = "kept")

        assertFailsWith<DatabaseStorageFullException> { storageFullDriverFactory(name).makeDriver() }

        assertEquals("kept", readMarker())
    }

    @Test
    fun `full disk hidden by a failed rollback keeps the existing database file`() {
        writeUnversionedDatabase(marker = "kept")

        val error = assertFails { rollbackMaskingDriverFactory().makeDriver() }

        // SQLiter's ROLLBACK fails after SQLite already rolled back, replacing SQLITE_FULL.
        assertIsNot<DatabaseStorageFullException>(error)
        assertTrue(error.message.orEmpty().contains("cannot rollback"), error.message)
        assertEquals("kept", readMarker())
    }

    @Test
    fun `open succeeds once storage is available`() {
        assertFailsWith<DatabaseStorageFullException> { storageFullDriverFactory(name).makeDriver() }

        val driver = DriverFactory(name).makeDriver()
        try {
            val collections = QuranDatabase(driver).collectionsQueries.getCollections().executeAsList()
            assertEquals(6, collections.size)
        } finally {
            driver.close()
        }
    }

    // A database at version 0 makes the next open create the schema, which needs new pages.
    private fun writeUnversionedDatabase(marker: String) {
        unversionedDatabase().withConnection { connection ->
            connection.withStatement("CREATE TABLE marker(value TEXT NOT NULL)") { execute() }
            connection.withStatement("INSERT INTO marker VALUES ('$marker')") { execute() }
        }
    }

    // Without a statement journal, SQLITE_FULL from a single-row insert rolls back the whole transaction,
    // as a real full disk does during schema creation.
    private fun rollbackMaskingDriverFactory() =
        DriverFactory(name) { configuration ->
            configuration.copy(
                create = { connection ->
                    connection.withStatement("CREATE TABLE big(v BLOB)") { execute() }
                    connection.longForQuery("PRAGMA max_page_count = 3")
                    connection.withStatement("INSERT INTO big VALUES (randomblob(100000))") { execute() }
                }
            )
        }

    private fun readMarker(): String =
        unversionedDatabase().withConnection { it.stringForQuery("SELECT value FROM marker") }

    private fun unversionedDatabase() =
        createDatabaseManager(DatabaseConfiguration(name = name, version = NO_VERSION_CHECK, create = {}))
}

/**
 * Returns a factory whose connections fail with SQLITE_FULL once the database needs a new page.
 *
 * SQLite cannot lower `max_page_count` below the current size, so the limit becomes the file's size.
 */
internal fun storageFullDriverFactory(name: String): DriverFactory =
    DriverFactory(name) { configuration ->
        configuration.copy(
            lifecycleConfig = configuration.lifecycleConfig.copy(
                onCreateConnection = { connection -> connection.longForQuery("PRAGMA max_page_count = 1") }
            )
        )
    }
