package com.quran.shared.pipeline

import com.quran.shared.persistence.MobileSyncDatabase

/** A fresh in-memory app database with its schema and foreign keys on, on each platform's own SQLite driver. */
expect fun inMemoryMobileSyncDatabase(): MobileSyncDatabase
