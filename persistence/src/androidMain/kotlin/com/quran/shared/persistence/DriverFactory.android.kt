package com.quran.shared.persistence

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DriverFactory(private val context: Context) {
    actual fun makeDriver(): SqlDriver {
        return AndroidSqliteDriver(
            schema = MobileSyncSchema,
            context = context,
            name = "quran.db",
            callback = object : AndroidSqliteDriver.Callback(MobileSyncSchema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)
                }
            }
        )
    }
}
