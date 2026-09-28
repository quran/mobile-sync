package com.quran.shared.persistence.repository.keyvalue

import com.quran.shared.di.AppScope
import com.quran.shared.persistence.QuranDatabase
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Stores sync and session lifecycle metadata in the shared database.
 *
 * Values survive [com.quran.shared.persistence.repository.PersistenceResetRepository.deleteAllData]
 * because session lifecycle state must outlive the user-data reset it coordinates.
 */
interface KeyValueRepository {
    suspend fun get(key: String): String?
    suspend fun keys(): Set<String>
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String)
    suspend fun clear()
}

@Inject
@SingleIn(AppScope::class)
class KeyValueRepositoryImpl(
    private val database: QuranDatabase
) : KeyValueRepository {

    private val queries = lazy { database.key_value_storeQueries }

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        queries.value.getValue(key).executeAsOneOrNull()
    }

    override suspend fun keys(): Set<String> = withContext(Dispatchers.IO) {
        queries.value.getKeys().executeAsList().toSet()
    }

    override suspend fun put(key: String, value: String) {
        withContext(Dispatchers.IO) {
            queries.value.putValue(key, value)
        }
    }

    override suspend fun remove(key: String) {
        withContext(Dispatchers.IO) {
            queries.value.removeValue(key)
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            queries.value.deleteAll()
        }
    }
}
