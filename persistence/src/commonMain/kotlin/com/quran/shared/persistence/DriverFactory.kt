package com.quran.shared.persistence

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import kotlin.concurrent.Volatile
import kotlin.native.HiddenFromObjC

/** Opens the app database, `quran.db`, with [MobileSyncSchema] and foreign keys on (the sync engine relies on them). */
expect class DriverFactory {
    fun makeDriver(): SqlDriver
}

/**
 * Global instance to ensure a single database and driver instance is shared across the entire app.
 */
@Volatile
private var database: MobileSyncDatabase? = null

@OptIn(InternalCoroutinesApi::class)
private val lock = SynchronizedObject()

/**
 * Returns the singleton [MobileSyncDatabase], creating it if necessary using the provided [driverFactory].
 */
@OptIn(InternalCoroutinesApi::class)
private fun getDatabase(driverFactory: DriverFactory): MobileSyncDatabase {
    return database ?: synchronized(lock) {
        database ?: MobileSyncDatabase(driverFactory.makeDriver()).also { database = it }
    }
}

/**
 * Opens the shared database, or reuses it when already open.
 *
 * The dependency graph resolves the same instance, so it does not open the database again. A failed
 * open caches nothing, so calling again retries. [driverFactory] is ignored when already open.
 *
 * The iOS driver opens the file here. Android's driver opens lazily, so its failures surface on first use.
 */
@HiddenFromObjC
fun openSharedDatabase(driverFactory: DriverFactory) {
    getDatabase(driverFactory)
}

internal fun makeDatabase(driverFactory: DriverFactory): MobileSyncDatabase = getDatabase(driverFactory)
