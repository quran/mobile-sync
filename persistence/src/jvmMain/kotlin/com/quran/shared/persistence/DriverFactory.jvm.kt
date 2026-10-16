package com.quran.shared.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties

actual class DriverFactory {
    actual fun makeDriver(): SqlDriver {
        return JdbcSqliteDriver("jdbc:sqlite:$DATABASE_NAME", Properties().apply { put("foreign_keys", "true") }, MobileSyncSchema)
    }

    internal actual fun deleteDatabase() {
        for (suffix in listOf("", "-journal", "-wal", "-shm")) {
            File(DATABASE_NAME + suffix).delete()
        }
    }

    private companion object {
        const val DATABASE_NAME = "user_data.db"
    }
}
