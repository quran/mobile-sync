package com.quran.shared.syncv2

import com.quran.shared.syncv2.db.SyncV2Database

/** A fresh in-memory database with the schema and foreign keys on, on each platform's own SQLite driver. */
expect fun inMemoryDatabase(): SyncV2Database
