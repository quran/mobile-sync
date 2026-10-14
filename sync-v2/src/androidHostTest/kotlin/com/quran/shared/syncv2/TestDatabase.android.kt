package com.quran.shared.syncv2

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.quran.shared.syncv2.db.SyncV2Database

actual fun inMemoryDatabase(): SyncV2Database = SyncV2Database(
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
        SyncV2Database.Schema.create(it)
        it.execute(null, "PRAGMA foreign_keys = ON", 0)
    }
)
