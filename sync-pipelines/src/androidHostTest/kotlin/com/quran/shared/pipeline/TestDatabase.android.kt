package com.quran.shared.pipeline

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.MobileSyncSchema
import java.util.Properties

actual fun inMemoryMobileSyncDatabase(): MobileSyncDatabase = MobileSyncDatabase(
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { put("foreign_keys", "true") })
        .also { MobileSyncSchema.create(it) }
)
