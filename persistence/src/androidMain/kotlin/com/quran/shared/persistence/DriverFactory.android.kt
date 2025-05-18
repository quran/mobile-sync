package com.quran.shared.persistence

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DriverFactory(private val context: Context) {
    actual fun makeDriver(): SqlDriver {
        return AndroidSqliteDriver(QuranDatabase.Schema, context, "quran.db")
    }
}