package com.quran.shared.syncv2

import app.cash.sqldelight.driver.native.inMemoryDriver
import com.quran.shared.syncv2.db.SyncV2Database

actual fun inMemoryDatabase(): SyncV2Database = SyncV2Database(
    inMemoryDriver(SyncV2Database.Schema).also { it.execute(null, "PRAGMA foreign_keys = ON", 0) }
)
