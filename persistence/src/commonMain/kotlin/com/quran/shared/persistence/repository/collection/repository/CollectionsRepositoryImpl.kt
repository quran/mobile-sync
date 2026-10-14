package com.quran.shared.persistence.repository.collection.repository

import app.cash.sqldelight.coroutines.asFlow
import co.touchlab.kermit.Logger
import com.quran.shared.di.AppScope
import com.quran.shared.persistence.DuplicateCollectionNameException
import com.quran.shared.persistence.ReadOnlyCollectionException
import com.quran.shared.persistence.TextLengthField
import com.quran.shared.persistence.model.Collection
import com.quran.shared.persistence.model.isSystemCollectionName
import com.quran.shared.persistence.repository.collection.CollectionStore
import com.quran.shared.persistence.repository.collection.isHighlight
import com.quran.shared.persistence.requireServerTextLength
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.currentPlatformDateTime
import com.quran.shared.persistence.util.toEpochMillisecondsFromPlatform
import com.quran.shared.persistence.util.toPlatform
import com.quran.shared.syncv2.db.SyncV2Database
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.native.HiddenFromObjC
import kotlin.time.Instant
import com.quran.shared.syncv2.db.Collection as CollectionRow

@Inject
@SingleIn(AppScope::class)
@HiddenFromObjC
class CollectionsRepositoryImpl(
    private val database: SyncV2Database
) : CollectionsRepository {

    private val logger = Logger.withTag("CollectionsRepository")
    private val store = CollectionStore(database)

    override suspend fun getAllCollections(): List<Collection> = withContext(Dispatchers.IO) {
        store.newestFirst().map { it.toCollection() }
    }

    override fun getCollectionsFlow(): Flow<List<Collection>> =
        database.collectionsQueries.collectionsNewestFirst().asFlow().map {
            withContext(Dispatchers.IO) { store.newestFirst().map { row -> row.toCollection() } }
        }

    override suspend fun addCollection(name: String): Collection = addCollection(name, currentPlatformDateTime())

    /** Adding a name that is taken (trimmed, case-insensitive) returns the existing collection. */
    override suspend fun addCollection(name: String, timestamp: PlatformDateTime): Collection {
        logger.i { "Adding collection with name=$name" }
        require(!isSystemCollectionName(name)) { "System collection name is reserved: $name." }
        requireServerTextLength(TextLengthField.COLLECTION_NAME, name)
        val at = timestamp.toEpochMillisecondsFromPlatform()
        return withContext(Dispatchers.IO) {
            database.transactionWithResult {
                val localId = store.byName(name)?.local_id ?: store.insertUser(name, at)
                requireNotNull(store.get(localId)).toCollection()
            }
        }
    }

    override suspend fun updateCollection(id: String, name: String): Collection =
        updateCollection(id, name, currentPlatformDateTime())

    override suspend fun updateCollection(id: String, name: String, timestamp: PlatformDateTime): Collection {
        logger.i { "Updating collection id=$id with name=$name" }
        val at = timestamp.toEpochMillisecondsFromPlatform()
        return withContext(Dispatchers.IO) {
            database.transactionWithResult {
                val existing = requireNotNull(id.toLongOrNull()?.let(store::get)) {
                    "Expected active collection id=$id before update."
                }
                if (existing.isReadOnly) throw ReadOnlyCollectionException(id)
                require(!isSystemCollectionName(name)) { "System collection name is reserved: $name." }
                requireServerTextLength(TextLengthField.COLLECTION_NAME, name)
                store.byName(name)?.takeIf { it.local_id != existing.local_id }?.let {
                    throw DuplicateCollectionNameException(it.name)
                }
                store.rename(existing, name, at)
                requireNotNull(store.get(existing.local_id)).toCollection()
            }
        }
    }

    override suspend fun deleteCollection(id: String): Boolean {
        logger.i { "Deleting collection id=$id" }
        return withContext(Dispatchers.IO) {
            database.transactionWithResult {
                val existing = id.toLongOrNull()?.let(store::get) ?: return@transactionWithResult false
                if (existing.isReadOnly) throw ReadOnlyCollectionException(id)
                store.delete(existing.local_id)
                true
            }
        }
    }
}

/** Favorites, highlights and the server's system collections are system collections; app-facing lists hide them. */
internal val CollectionRow.isSystemCollection: Boolean get() = is_default == 1L || is_system == 1L || isHighlight

/** No rename or delete: system collections and the older apps' protected color collections. */
internal val CollectionRow.isReadOnly: Boolean get() = isSystemCollection || is_read_only == 1L

internal fun CollectionRow.toCollection(): Collection = Collection(
    name = name,
    lastUpdated = Instant.fromEpochMilliseconds(updated_at).toPlatform(),
    id = local_id.toString(),
    isDefault = is_default == 1L,
    isSystem = isSystemCollection,
    isReadOnly = isReadOnly
)

