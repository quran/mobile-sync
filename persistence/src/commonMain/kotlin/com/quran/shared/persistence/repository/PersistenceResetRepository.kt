package com.quran.shared.persistence.repository

import com.quran.shared.syncv2.SyncEngineV2
import com.quran.shared.syncv2.db.SyncV2Database
import com.quran.shared.syncv2.types.syncTypes
import dev.zacsweers.metro.Inject
import kotlin.native.HiddenFromObjC

interface PersistenceResetRepository {
    fun deleteAllData()
}

/**
 * Deletes the user's data and its sync state (bases, token, account, outbox) in one transaction, and bumps the wipe
 * epoch, so a sync in flight can no longer commit. Favorites is seeded again.
 *
 * The key-value store and the import history are kept. The history records what this device's legacy data already
 * brought in, which a logout doesn't change: clearing it would let a later tracked import bring back items the user
 * deleted while signed in, and push them as new on the next login, to this account or another one (S14).
 */
@Inject
@HiddenFromObjC
class PersistenceResetRepositoryImpl(
    private val synced: SyncV2Database
) : PersistenceResetRepository {
    override fun deleteAllData() = SyncEngineV2.wipe(synced, syncTypes(synced))
}
