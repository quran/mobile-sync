package com.quran.shared.persistence

import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.toPlatform
import com.quran.shared.syncv2.HeldItem
import com.quran.shared.syncv2.ItemRef
import com.quran.shared.syncv2.PushPlanner
import com.quran.shared.syncv2.Refs
import com.quran.shared.syncv2.SyncEngineV2
import com.quran.shared.syncv2.SyncRow
import com.quran.shared.syncv2.SyncStore
import com.quran.shared.syncv2.SyncType
import com.quran.shared.syncv2.types.syncTypes
import kotlin.time.Instant

/** A fresh in-memory app database: [MobileSyncSchema] (Favorites seeded) with foreign keys on. */
internal fun createTestDatabase(): MobileSyncDatabase = MobileSyncDatabase(TestDatabaseDriver().createDriver())

internal fun at(milliseconds: Long): PlatformDateTime = Instant.fromEpochMilliseconds(milliseconds).toPlatform()

/** Records every [type] row as the server has it now, as a sync would, so only later edits are pending. */
internal fun MobileSyncDatabase.markSynced(type: SyncType) {
    val store = SyncStore(synced)
    type.readAll().forEach { (localId, state) -> store.put(type.name, SyncRow(localId, "remote-$localId", state)) }
}

/** The items the next push would send. */
internal fun MobileSyncDatabase.pending(): List<ItemRef> = SyncEngineV2.unsynced(synced, syncTypes(synced)).pending

/** Plans a push without sending it, as a sync does first, which records the holds of changes the server would refuse. */
internal fun MobileSyncDatabase.planPush() {
    val store = SyncStore(synced)
    val refs = object : Refs {
        override fun remoteId(type: SyncType, localId: Long): String? = store.row(type.name, localId)?.remoteId
        override fun localId(type: SyncType, remoteId: String): Long? = store.rowByRemote(type.name, remoteId)?.localId
    }
    synced.transaction { PushPlanner(store, syncTypes(synced), refs).plan() }
}

/** The items the next push would hold back, and why. */
internal fun MobileSyncDatabase.held(): List<HeldItem> = SyncEngineV2.unsynced(synced, syncTypes(synced)).held
