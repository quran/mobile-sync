package com.quran.shared.syncv2.types

import com.quran.shared.syncv2.SyncType
import com.quran.shared.syncv2.db.SyncV2Database

/**
 * The synced types over [database], in dependency order (parents first): the list a [com.quran.shared.syncv2.SyncEngineV2]
 * syncs and a wipe clears.
 */
fun syncTypes(database: SyncV2Database): List<SyncType> {
    val collections = CollectionsType(database)
    return listOf(
        collections,
        CollectionAyahsType(database, collections),
        ReadingSessionsType(database),
        NotesType(database),
        ReadingBookmarksType(database)
    )
}
