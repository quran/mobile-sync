package com.quran.shared.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
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
    fun `the collections a verse is in are found through an index`() {
        val plan = queryPlan("SELECT * FROM collection_ayah WHERE sura = 2 AND ayah = 255 ORDER BY local_id")

        // Without it, an import's lookup per verse scans every saved verse: quadratic in the import.
        assertTrue(plan.any { "collection_ayah_verse_idx" in it }, "query plan: $plan")
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

    private fun queryPlan(sql: String): List<String> = driver.executeQuery(
        identifier = null,
        sql = "EXPLAIN QUERY PLAN $sql",
        mapper = { cursor ->
            val steps = mutableListOf<String>()
            while (cursor.next().value) steps += cursor.getString(3).orEmpty()
            QueryResult.Value(steps)
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
