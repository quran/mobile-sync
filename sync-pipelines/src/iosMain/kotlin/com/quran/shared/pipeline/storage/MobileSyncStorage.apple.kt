@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.quran.shared.pipeline.storage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.russhwolf.settings.datastore.DataStoreSettings
import kotlin.concurrent.Volatile
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import okio.Path.Companion.toPath
import org.publicvalue.multiplatform.oidc.tokenstore.IosKeychainTokenStore
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

@OptIn(InternalCoroutinesApi::class)
private val storageLock = SynchronizedObject()

@Volatile
private var cachedStorage: MobileSyncStorage? = null

/**
 * Creates the Apple storage configuration for [com.quran.shared.pipeline.di.SharedDependencyGraph].
 *
 * The returned instance is process-singleton so app setup cannot accidentally create more than one
 * DataStore for the sync metadata file.
 */
@OptIn(InternalCoroutinesApi::class)
fun createMobileSyncStorage(): MobileSyncStorage {
    return cachedStorage ?: synchronized(storageLock) {
        cachedStorage ?: buildMobileSyncStorage().also { cachedStorage = it }
    }
}

/**
 * Object-style factory for Swift call sites.
 */
object AppleMobileSyncStorageFactory {
    fun create(): MobileSyncStorage = createMobileSyncStorage()
}

private fun buildMobileSyncStorage(): MobileSyncStorage {
    val dataStore = PreferenceDataStoreFactory.createWithPath {
        "${applicationSupportDirectory().path}/${MobileSyncStorageNames.SYNC_SETTINGS_DATASTORE_FILE_NAME}".toPath()
    }

    return MobileSyncStorage(
        tokenStore = IosKeychainTokenStore(),
        settings = DataStoreSettings(dataStore)
    )
}

private fun applicationSupportDirectory(): NSURL {
    return NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null
    ) ?: error("Unable to resolve Application Support directory for mobile-sync storage")
}
