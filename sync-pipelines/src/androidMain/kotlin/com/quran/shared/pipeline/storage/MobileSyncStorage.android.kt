package com.quran.shared.pipeline.storage

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.russhwolf.settings.datastore.DataStoreSettings
import java.io.File
import kotlin.concurrent.Volatile
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import okio.Path.Companion.toPath
import org.publicvalue.multiplatform.oidc.tokenstore.AndroidSettingsTokenStore

@OptIn(InternalCoroutinesApi::class)
private val storageLock = SynchronizedObject()

@Volatile
private var cachedStorage: MobileSyncStorage? = null

/**
 * Creates the Android storage configuration for [com.quran.shared.pipeline.di.SharedDependencyGraph].
 *
 * The returned instance is process-singleton so repeated Activity creation does not create multiple
 * DataStore instances for the same file.
 *
 * @param context application or activity context. The application context is retained.
 */
@OptIn(InternalCoroutinesApi::class)
fun createMobileSyncStorage(context: Context): MobileSyncStorage {
    val appContext = context.applicationContext
    return cachedStorage ?: synchronized(storageLock) {
        cachedStorage ?: buildMobileSyncStorage(appContext).also { cachedStorage = it }
    }
}

/**
 * Object-style factory for Java and Android call sites that prefer a named owner.
 */
object AndroidMobileSyncStorageFactory {
    fun create(context: Context): MobileSyncStorage = createMobileSyncStorage(context)
}

private fun buildMobileSyncStorage(context: Context): MobileSyncStorage {
    val dataStore = PreferenceDataStoreFactory.createWithPath {
        val file = File(
            context.filesDir,
            MobileSyncStorageNames.ANDROID_SYNC_SETTINGS_BACKUP_PATH
        )
        file.parentFile?.mkdirs()
        file.absolutePath.toPath()
    }

    return MobileSyncStorage(
        tokenStore = AndroidSettingsTokenStore(context),
        settings = DataStoreSettings(dataStore)
    )
}
