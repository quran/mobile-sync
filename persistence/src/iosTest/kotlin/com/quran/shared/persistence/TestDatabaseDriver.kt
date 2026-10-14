package com.quran.shared.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseFileContext
import kotlin.random.Random

actual class TestDatabaseDriver {
    actual fun createDriver(): SqlDriver {
        val name = "test_${Random.nextInt().toUInt()}.db"
        DatabaseFileContext.deleteDatabase(name)
        // A file database, like the app's, so every connection of the pool sees the same data.
        return NativeSqliteDriver(MobileSyncSchema, name, onConfiguration = { configuration ->
            configuration.copy(extendedConfig = configuration.extendedConfig.copy(foreignKeyConstraints = true))
        })
    }
}
