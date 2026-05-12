package com.quran.shared.pipeline.storage

import com.russhwolf.settings.coroutines.FlowSettings
import org.publicvalue.multiplatform.oidc.tokenstore.TokenStore

/**
 * App-provided storage dependencies used by the shared sync graph.
 *
 * The public surface is intentionally opaque so app code does not need to depend on raw DataStore
 * APIs. Platform factory helpers create a secure [TokenStore] for OAuth tokens and a named
 * DataStore-backed settings instance for non-token sync/session metadata.
 */
class MobileSyncStorage internal constructor(
    internal val tokenStore: TokenStore,
    internal val settings: FlowSettings
)

/**
 * Stable storage names and Android backup paths used by the shared library.
 *
 * Android apps that enable backup can exclude [ANDROID_SYNC_SETTINGS_BACKUP_PATH] and
 * [ANDROID_OIDC_TOKENSTORE_BACKUP_PATH] from cloud backup and device transfer. The sync timestamp is
 * derived remote-state cache, and restored token-store data can be invalid after Android keystore
 * changes, so both files should normally be excluded.
 */
object MobileSyncStorageNames {
    const val SYNC_SETTINGS_DATASTORE_FILE_NAME = "quran_mobile_sync_settings.preferences_pb"
    const val ANDROID_SYNC_SETTINGS_BACKUP_PATH = "datastore/$SYNC_SETTINGS_DATASTORE_FILE_NAME"
    const val ANDROID_OIDC_TOKENSTORE_BACKUP_PATH =
        "datastore/org.publicvalue.multiplatform.oidc.tokenstore.preferences_pb"
}
