package com.quran.shared.pipeline.storage

import com.quran.shared.persistence.repository.keyvalue.KeyValueRepository
import com.russhwolf.settings.coroutines.SuspendSettings

/**
 * [SuspendSettings] backed by the shared SQLite database so sync and session lifecycle metadata
 * lives next to the user data it describes.
 *
 * Values are stored as text. Reading a key with a type it was not written with returns the
 * default, matching the other multiplatform-settings implementations.
 */
internal class DatabaseSettings(
    private val repository: KeyValueRepository
) : SuspendSettings {

    override suspend fun keys(): Set<String> = repository.keys()

    override suspend fun size(): Int = repository.keys().size

    override suspend fun clear() = repository.clear()

    override suspend fun remove(key: String) = repository.remove(key)

    override suspend fun hasKey(key: String): Boolean = repository.get(key) != null

    override suspend fun putInt(key: String, value: Int) = repository.put(key, value.toString())

    override suspend fun getInt(key: String, defaultValue: Int): Int = getIntOrNull(key) ?: defaultValue

    override suspend fun getIntOrNull(key: String): Int? = repository.get(key)?.toIntOrNull()

    override suspend fun putLong(key: String, value: Long) = repository.put(key, value.toString())

    override suspend fun getLong(key: String, defaultValue: Long): Long = getLongOrNull(key) ?: defaultValue

    override suspend fun getLongOrNull(key: String): Long? = repository.get(key)?.toLongOrNull()

    override suspend fun putString(key: String, value: String) = repository.put(key, value)

    override suspend fun getString(key: String, defaultValue: String): String =
        getStringOrNull(key) ?: defaultValue

    override suspend fun getStringOrNull(key: String): String? = repository.get(key)

    override suspend fun putFloat(key: String, value: Float) = repository.put(key, value.toString())

    override suspend fun getFloat(key: String, defaultValue: Float): Float =
        getFloatOrNull(key) ?: defaultValue

    override suspend fun getFloatOrNull(key: String): Float? = repository.get(key)?.toFloatOrNull()

    override suspend fun putDouble(key: String, value: Double) = repository.put(key, value.toString())

    override suspend fun getDouble(key: String, defaultValue: Double): Double =
        getDoubleOrNull(key) ?: defaultValue

    override suspend fun getDoubleOrNull(key: String): Double? = repository.get(key)?.toDoubleOrNull()

    override suspend fun putBoolean(key: String, value: Boolean) = repository.put(key, value.toString())

    override suspend fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        getBooleanOrNull(key) ?: defaultValue

    override suspend fun getBooleanOrNull(key: String): Boolean? =
        repository.get(key)?.toBooleanStrictOrNull()
}
