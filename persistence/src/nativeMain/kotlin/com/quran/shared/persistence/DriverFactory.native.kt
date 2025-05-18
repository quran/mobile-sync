package com.quran.shared.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DriverFactory {
    actual fun makeDriver(): SqlDriver {
        return NativeSqliteDriver(QuranDatabase.Schema, "quran.db")
    }
}