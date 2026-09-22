package com.quran.shared.persistence.repository.collection

import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.DatabaseCollection
import com.quran.shared.persistence.model.highlightColorForCollectionName
import com.quran.shared.persistence.model.isSystemCollectionName

/** Transaction-scoped access to persisted collections. */
internal class CollectionStore(
    private val database: QuranDatabase
) {
    private val queries = database.collectionsQueries

    fun activeCollections(): List<DatabaseCollection> = queries.getCollections().executeAsList()

    fun activeHighlightCollections(): List<Pair<AyahHighlightColor, DatabaseCollection>> =
        activeCollections().mapNotNull { collection ->
            highlightColorForCollectionName(collection.name)?.let { it to collection }
        }

    fun requireDefault(): DatabaseCollection =
        requireNotNull(queries.getDefaultCollection().executeAsOneOrNull()) {
            "Default collection is missing."
        }

    fun add(name: String, timestampMillis: Long): DatabaseCollection {
        require(!isSystemCollectionName(name)) {
            "System collection name is reserved: $name."
        }
        queries.addNewCollection(
            name = name,
            timestamp = timestampMillis,
            is_system = 0L
        )
        return requireUserCollection(name, "after insert")
    }

    fun getOrCreateActiveHighlight(
        color: AyahHighlightColor,
        timestampMillis: Long,
        knownCollections: List<Pair<AyahHighlightColor, DatabaseCollection>> = activeHighlightCollections()
    ): HighlightCollectionResolution {
        val collection = knownCollections.firstOrNull { it.first == color }?.second
            ?: createHighlight(color, timestampMillis)
        return activateHighlight(collection, timestampMillis)
    }

    private fun createHighlight(
        color: AyahHighlightColor,
        timestampMillis: Long
    ): DatabaseCollection {
        queries.addNewCollection(
            name = color.collectionName,
            timestamp = timestampMillis,
            is_system = 1L
        )
        return requireNotNull(queries.getCollectionByName(color.collectionName).executeAsOneOrNull()) {
            "Expected highlight collection ${color.collectionName} after insert."
        }
    }

    private fun activateHighlight(
        collection: DatabaseCollection,
        timestampMillis: Long
    ): HighlightCollectionResolution {
        val changed = collection.remote_id == null && collection.pending_version == 0L
        if (changed) {
            queries.addNewCollection(
                name = collection.name,
                timestamp = timestampMillis,
                is_system = 1L
            )
        }
        val active = requireNotNull(queries.getCollectionByLocalId(collection.local_id).executeAsOneOrNull()) {
            "Expected active highlight collection ${collection.name}."
        }
        return HighlightCollectionResolution(active, changed)
    }

    private fun requireUserCollection(name: String, operation: String): DatabaseCollection {
        val collection = requireNotNull(queries.getCollectionByName(name).executeAsOneOrNull()) {
            "Expected collection for name=$name $operation."
        }
        require(collection.is_system == 0L) { "System collection name is reserved: $name." }
        return collection
    }
}

internal data class HighlightCollectionResolution(
    val collection: DatabaseCollection,
    val changed: Boolean
)
