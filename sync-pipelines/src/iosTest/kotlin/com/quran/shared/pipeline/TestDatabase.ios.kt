package com.quran.shared.pipeline

import app.cash.sqldelight.driver.native.inMemoryDriver
import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.MobileSyncSchema

actual fun inMemoryMobileSyncDatabase(): MobileSyncDatabase = MobileSyncDatabase(
    inMemoryDriver(MobileSyncSchema).also { it.execute(null, "PRAGMA foreign_keys = ON", 0) }
)
