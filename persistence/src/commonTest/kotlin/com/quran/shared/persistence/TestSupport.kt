package com.quran.shared.persistence

import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.toPlatform
import kotlin.time.Instant

/** A fresh in-memory app database: [MobileSyncSchema] (Favorites seeded) with foreign keys on. */
internal fun createTestDatabase(): MobileSyncDatabase = MobileSyncDatabase(TestDatabaseDriver().createDriver())

internal fun at(milliseconds: Long): PlatformDateTime = Instant.fromEpochMilliseconds(milliseconds).toPlatform()
