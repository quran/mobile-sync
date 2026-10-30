package com.quran.shared.persistence

import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.toPlatform
import com.quran.shared.syncv2.ItemRef
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
