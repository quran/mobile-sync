package com.quran.shared.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlin.random.Random

actual class TestDatabaseDriver {
    actual fun createDriver(): SqlDriver {
        val uniqueId = Random.nextInt()
        return NativeSqliteDriver(QuranDatabase.Schema, "test_${uniqueId}.db")
    }
} 