package com.quran.shared.pipeline.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.repository.keyvalue.KeyValueRepositoryImpl
import com.quran.shared.pipeline.SettingsSessionLifecycleStateStore
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseSettingsTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: QuranDatabase

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        QuranDatabase.Schema.create(driver)
        database = QuranDatabase(driver)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun newSettings() = DatabaseSettings(KeyValueRepositoryImpl(database))

    @Test
    fun `typed values round trip through the database`() = runTest {
        val settings = newSettings()
        settings.putInt("int", 7)
        settings.putLong("long", Long.MAX_VALUE)
        settings.putString("string", "value")
        settings.putFloat("float", 1.5f)
        settings.putDouble("double", 2.25)
        settings.putBoolean("boolean", true)

        val reopened = newSettings()
        assertEquals(7, reopened.getIntOrNull("int"))
        assertEquals(Long.MAX_VALUE, reopened.getLongOrNull("long"))
        assertEquals("value", reopened.getStringOrNull("string"))
        assertEquals(1.5f, reopened.getFloatOrNull("float"))
        assertEquals(2.25, reopened.getDoubleOrNull("double"))
        assertEquals(true, reopened.getBooleanOrNull("boolean"))
        assertEquals(6, reopened.size())
    }

    @Test
    fun `missing and mismatched values fall back to defaults`() = runTest {
        val settings = newSettings()
        settings.putString("text", "not-a-number")

        assertEquals(3L, settings.getLong("missing", 3L))
        assertNull(settings.getLongOrNull("text"))
        assertEquals(false, settings.getBoolean("text", false))
        assertTrue(settings.hasKey("text"))
        assertFalse(settings.hasKey("missing"))
    }

    @Test
    fun `remove and clear delete stored values`() = runTest {
        val settings = newSettings()
        settings.putLong("a", 1L)
        settings.putLong("b", 2L)

        settings.remove("a")
        assertEquals(setOf("b"), settings.keys())

        settings.clear()
        assertEquals(emptySet(), settings.keys())
    }

    @Test
    fun `session lifecycle state persists in the database`() = runTest {
        val resetState = SettingsSessionLifecycleStateStore(newSettings()).beginReset()

        assertEquals(resetState, SettingsSessionLifecycleStateStore(newSettings()).snapshot())
    }
}
