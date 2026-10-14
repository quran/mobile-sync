package com.quran.shared.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobileSyncSchemaTest {
    private lateinit var driver: SqlDriver

    @BeforeTest
    fun setup() {
        driver = TestDatabaseDriver().createDriver()
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a new database has the app tables and the synced tables`() {
        assertTrue(tables().containsAll(APP_TABLES + SYNCED_TABLES))
    }

    @Test
    fun `a new database has Favorites`() {
        val favorites = MobileSyncDatabase(driver).synced.collectionsQueries.collectionDefault().executeAsOne()

        assertEquals("Favorites", favorites.name)
        assertEquals(1L, favorites.is_read_only)
    }

    @Test
    fun `migrating a version 1 database replaces its tables with the new schema`() {
        createVersionOneTables()

        MobileSyncSchema.migrate(driver, oldVersion = 1, newVersion = MobileSyncSchema.version)

        val tables = tables()
        assertFalse("bookmarks" in tables)
        assertFalse("bookmark_collections" in tables)
        assertTrue(tables.containsAll(APP_TABLES + SYNCED_TABLES))
    }

    @Test
    fun `migrating a version 1 database drops its data and seeds Favorites`() {
        val database = MobileSyncDatabase(driver)
        database.synced.collectionsQueries.collectionInsert("Study", "study", 0, 0, 0, 100, 100)
        database.quran.key_value_storeQueries.putValue("key", "value")
        createVersionOneTables()

        MobileSyncSchema.migrate(driver, oldVersion = 1, newVersion = MobileSyncSchema.version)

        assertEquals(listOf("Favorites"), database.synced.collectionsQueries.collectionsAll().executeAsList().map { it.name })
        assertEquals(emptyList(), database.quran.key_value_storeQueries.getKeys().executeAsList())
    }

    // The old sync engine's tables: domain rows with sync columns, and a link table referencing them.
    private fun createVersionOneTables() {
        driver.execute(
            null,
            "CREATE TABLE bookmarks(local_id INTEGER PRIMARY KEY, remote_id TEXT, sura INTEGER, ayah INTEGER, deleted INTEGER NOT NULL DEFAULT 0)",
            0
        )
        driver.execute(
            null,
            "CREATE TABLE bookmark_collections(local_id INTEGER PRIMARY KEY, bookmark_local_id INTEGER NOT NULL REFERENCES bookmarks(local_id))",
            0
        )
        driver.execute(null, "INSERT INTO bookmarks(local_id, remote_id, sura, ayah) VALUES (1, 'remote', 2, 255)", 0)
        driver.execute(null, "INSERT INTO bookmark_collections(local_id, bookmark_local_id) VALUES (1, 1)", 0)
    }

    private fun tables(): Set<String> = driver.executeQuery(
        identifier = null,
        sql = "SELECT name FROM sqlite_master WHERE type = 'table'",
        mapper = { cursor ->
            val names = mutableSetOf<String>()
            while (cursor.next().value) names += cursor.getString(0)!!
            QueryResult.Value(names)
        },
        parameters = 0
    ).value

    private companion object {
        val APP_TABLES = setOf("key_value_store", "import_history")
        val SYNCED_TABLES = setOf(
            "note", "note_range", "collection", "collection_ayah", "reading_session", "reading_bookmark", "sync_item", "sync_meta"
        )
    }
}
