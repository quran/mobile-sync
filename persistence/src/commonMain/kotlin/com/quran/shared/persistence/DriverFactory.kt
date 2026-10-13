package com.quran.shared.persistence

import app.cash.sqldelight.db.SqlDriver
import com.quran.shared.persistence.model.CollectionName
import com.quran.shared.persistence.model.CollectionNameKey
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import kotlin.concurrent.Volatile
import kotlin.native.HiddenFromObjC

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
        database ?: createQuranDatabase(driverFactory.makeDriver()).also { database = it }
    }
}

/** Creates a [QuranDatabase] on [driver] with the column adapters for its typed columns. */
@HiddenFromObjC
fun createQuranDatabase(driver: SqlDriver): QuranDatabase = QuranDatabase(
    driver = driver,
    collectionAdapter = Collection.Adapter(
        nameAdapter = CollectionName.Adapter,
        name_keyAdapter = CollectionNameKey.Adapter
    )
)

/**
 * Opens the shared [QuranDatabase], or reuses it when already open.
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

/**
 * Legacy helper function to maintain compatibility.
 * Now uses the singleton [getDatabase] internally.
 */
internal fun makeDatabase(driverFactory: DriverFactory): QuranDatabase = getDatabase(driverFactory)
