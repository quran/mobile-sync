package com.quran.shared.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.*

actual class DriverFactory {
    actual fun makeDriver(): SqlDriver {
        return JdbcSqliteDriver("jdbc:sqlite:quran.db", Properties(), QuranDatabase.Schema)
    }
}