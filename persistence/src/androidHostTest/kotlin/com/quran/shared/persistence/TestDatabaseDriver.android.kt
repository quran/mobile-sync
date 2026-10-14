package com.quran.shared.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties

actual class TestDatabaseDriver {
    actual fun createDriver(): SqlDriver = JdbcSqliteDriver(
        JdbcSqliteDriver.IN_MEMORY,
        Properties().apply {
            setProperty("limit_variable_number", "999")
            put("foreign_keys", "true")
        },
        MobileSyncSchema
    )
}
