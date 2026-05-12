package com.quran.shared.pipeline

import com.quran.shared.di.AppScope
import com.quran.shared.syncengine.LocalModificationDateFetcher
import com.russhwolf.settings.coroutines.SuspendSettings
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Persists the shared remote sync token used to fetch incremental mutations.
 */
interface SyncLocalModificationDateStore : LocalModificationDateFetcher {
    /**
     * Stores the newest completed remote mutation timestamp.
     *
     * @param date epoch-millisecond mutation timestamp returned by the sync API.
     */
    suspend fun updateLastModificationDate(date: Long)
}

/**
 * [SyncLocalModificationDateStore] backed by the graph-provided DataStore settings instance.
 */
@SingleIn(AppScope::class)
class SyncSettingsLocalModificationDateStore @Inject constructor(
    private val settings: SuspendSettings
) : SyncLocalModificationDateStore {

    override suspend fun localLastModificationDate(): Long {
        return settings.getLong(KEY_LAST_MODIFIED, 0L)
    }

    override suspend fun updateLastModificationDate(date: Long) {
        settings.putLong(KEY_LAST_MODIFIED, date)
    }

    private companion object {
        const val KEY_LAST_MODIFIED = "com.quran.sync.last_modified_date"
    }
}
