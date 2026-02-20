package com.quran.shared.persistence

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import kotlin.concurrent.Volatile

expect class DriverFactory {
    fun makeDriver(): SqlDriver
}

/**
 * Global instance to ensure a single database and driver instance is shared across the entire app.
 */
@Volatile
private var database: QuranDatabase? = null

@OptIn(InternalCoroutinesApi::class)
private val lock = SynchronizedObject()

/**
 * Returns the singleton instance of [QuranDatabase], creating it if necessary using the provided [driverFactory].
 *
 * @param driverFactory The factory to create the [SqlDriver] if the database hasn't been initialized.
 * @return The shared [QuranDatabase] instance.
 */
@OptIn(InternalCoroutinesApi::class)
private fun getDatabase(driverFactory: DriverFactory): QuranDatabase {
    return database ?: synchronized(lock) {
        database ?: QuranDatabase(driverFactory.makeDriver()).also { database = it }
    }
}

/**
 * Legacy helper function to maintain compatibility.
 * Now uses the singleton [getDatabase] internally.
 */
internal fun makeDatabase(driverFactory: DriverFactory): QuranDatabase = getDatabase(driverFactory)
