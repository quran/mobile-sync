package com.quran.shared.pipeline.storage

import dev.zacsweers.metro.Qualifier

/**
 * Qualifies the database-backed settings used for sync and session lifecycle metadata.
 *
 * Unqualified settings are the auth metadata DataStore, which must survive database loss.
 */
@Qualifier
internal annotation class SyncMetadataSettings
