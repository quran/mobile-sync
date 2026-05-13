package com.quran.shared.pipeline.storage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.russhwolf.settings.datastore.DataStoreSettings
import java.io.File
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import okio.Path.Companion.toPath
import org.publicvalue.multiplatform.oidc.tokenstore.SettingsStore
import org.publicvalue.multiplatform.oidc.tokenstore.SettingsTokenStore

@OptIn(InternalCoroutinesApi::class)
private val storageLock = SynchronizedObject()

private val cachedStorageByDirectory = mutableMapOf<String, MobileSyncStorage>()

/**
 * Creates a JVM storage configuration for tests and JVM sample hosts.
 *
 * Calls using the same normalized directory share one storage instance so DataStore does not
 * create competing active stores for the same file. Different directories receive independent
 * storage instances.
 *
 * @param directory directory where the sync metadata DataStore file should live.
 */
@OptIn(InternalCoroutinesApi::class)
fun createMobileSyncStorage(
    directory: File = File(System.getProperty("java.io.tmpdir"), "quran-mobile-sync")
): MobileSyncStorage {
    val storageDirectory = directory.toPath().toAbsolutePath().normalize().toFile()
    val cacheKey = storageDirectory.absolutePath

    return synchronized(storageLock) {
        cachedStorageByDirectory.getOrPut(cacheKey) {
            buildMobileSyncStorage(storageDirectory)
        }
    }
}

private fun buildMobileSyncStorage(directory: File): MobileSyncStorage {
    val dataStore = PreferenceDataStoreFactory.createWithPath {
        directory.mkdirs()
        File(
            directory,
            MobileSyncStorageNames.SYNC_SETTINGS_DATASTORE_FILE_NAME
        ).absolutePath.toPath()
    }

    return MobileSyncStorage(
        tokenStore = SettingsTokenStore(InMemorySettingsStore()),
        settings = DataStoreSettings(dataStore)
    )
}

private class InMemorySettingsStore : SettingsStore {
    private val values = mutableMapOf<String, String>()

    override suspend fun get(key: String): String? = values[key]

    override suspend fun put(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }

    override suspend fun clear() {
        values.clear()
    }
}
