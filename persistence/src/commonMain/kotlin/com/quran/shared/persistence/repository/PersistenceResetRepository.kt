package com.quran.shared.persistence.repository

import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.syncv2.SyncEngineV2
import com.quran.shared.syncv2.db.SyncV2Database
import com.quran.shared.syncv2.types.syncTypes
import dev.zacsweers.metro.Inject
import kotlin.native.HiddenFromObjC

interface PersistenceResetRepository {
    fun deleteAllData()
}

/**
 * Deletes the user's data, its sync state (bases, token, account, outbox) and the import history, in one transaction.
 * A sync in flight can no longer commit. Favorites is seeded again; the key-value store is kept.
 */
@Inject
@HiddenFromObjC
class PersistenceResetRepositoryImpl(
    private val database: QuranDatabase,
    private val synced: SyncV2Database
) : PersistenceResetRepository {
    override fun deleteAllData() {
        database.transaction {
            database.import_trackingQueries.clearImportHistory()
            SyncEngineV2.wipe(synced, syncTypes(synced))
        }
    }
}
