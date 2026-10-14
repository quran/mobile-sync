package com.quran.shared.persistence.repository.collection

import com.quran.shared.mutations.collectionNameKey
import com.quran.shared.mutations.trimLikeServer
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.highlightColorForCollectionName
import com.quran.shared.syncv2.db.Collection_ayah
import com.quran.shared.syncv2.db.SyncV2Database
import com.quran.shared.syncv2.types.CollectionsType
import com.quran.shared.syncv2.db.Collection as CollectionRow

/**
 * The one write path for collections and the verses in them (Favorites, highlights and the user's collections), so
 * every local write keeps the same rules: names trimmed like the server, one row per (collection, verse), and one
 * highlight color per verse. Callers run writes in a transaction.
 */
internal class CollectionStore(private val database: SyncV2Database) {
    private val queries get() = database.collectionsQueries

    fun all(): List<CollectionRow> = queries.collectionsAll().executeAsList()

    /** The app-facing order: newest first (by creation time), ties by the newer row. */
    fun newestFirst(): List<CollectionRow> = queries.collectionsNewestFirst().executeAsList()

    fun get(localId: Long): CollectionRow? = queries.collectionGet(localId).executeAsOneOrNull()

    fun byName(name: String): CollectionRow? = queries.collectionByKey(collectionNameKey(name)).executeAsOneOrNull()

    fun favorites(): CollectionRow = queries.collectionDefault().executeAsOne()

    /** Adds a user collection; the name is stored trimmed like the server. */
    fun insertUser(name: String, createdAt: Long, updatedAt: Long = createdAt): Long {
        val trimmed = name.trimLikeServer()
        queries.collectionInsert(trimmed, collectionNameKey(trimmed), 0, 0, 0, createdAt, updatedAt)
        return queries.lastInsertId().executeAsOne()
    }

    fun rename(row: CollectionRow, name: String, updatedAt: Long) {
        val trimmed = name.trimLikeServer()
        queries.collectionUpdate(trimmed, collectionNameKey(trimmed), row.is_system, row.is_read_only, updatedAt, row.local_id)
    }

    /** Deletes the collection and the verses in it. */
    fun delete(localId: Long) {
        queries.collectionAyahsDeleteIn(localId)
        queries.collectionDelete(localId)
    }

    fun highlightCollections(): Map<AyahHighlightColor, CollectionRow> =
        all().mapNotNull { row -> highlightColorForCollectionName(row.name)?.let { it to row } }.toMap()

    /**
     * The highlight collection for [color], created when missing. It is a system collection, as the server's GET
     * reports it; the client may create it, but never rename or delete it.
     */
    fun highlightCollection(color: AyahHighlightColor, at: Long): CollectionRow {
        byName(color.collectionName)?.let { return it }
        val state = CollectionsType.collectionState(color.collectionName, at, system = true)
        val localId = CollectionsType(database).insert(state)
        return requireNotNull(get(localId)) { "Expected highlight collection ${color.collectionName} after insert." }
    }

    fun versesIn(collectionLocalId: Long): List<Collection_ayah> = queries.collectionAyahsIn(collectionLocalId).executeAsList()

    fun allVerses(): List<Collection_ayah> = queries.collectionAyahsAll().executeAsList()

    /** Every collection [sura]:[ayah] is in. */
    fun membershipsOf(sura: Int, ayah: Int): List<Collection_ayah> =
        queries.collectionAyahsAt(sura.toLong(), ayah.toLong()).executeAsList()

    fun membership(collectionLocalId: Long, sura: Int, ayah: Int): Collection_ayah? =
        queries.collectionAyahFind(collectionLocalId, sura.toLong(), ayah.toLong()).executeAsOneOrNull()

    /** Adds the verse to the collection, dated [at]; returns false when it was already there. */
    fun addVerse(collectionLocalId: Long, sura: Int, ayah: Int, at: Long): Boolean {
        if (membership(collectionLocalId, sura, ayah) != null) return false
        queries.collectionAyahInsert(collectionLocalId, sura.toLong(), ayah.toLong(), at)
        return true
    }

    /** Removes the verse from the collection; returns false when it was not there. */
    fun removeVerse(collectionLocalId: Long, sura: Int, ayah: Int): Boolean {
        val membership = membership(collectionLocalId, sura, ayah) ?: return false
        queries.collectionAyahDelete(membership.local_id)
        return true
    }

    /**
     * Highlights the verse in [color] and removes its other colors: one color per verse. Returns the membership,
     * which keeps its date when the verse already had this color.
     */
    fun setHighlight(sura: Int, ayah: Int, color: AyahHighlightColor, at: Long): Collection_ayah {
        val target = highlightCollection(color, at)
        highlightCollections().values.filter { it.local_id != target.local_id }
            .forEach { removeVerse(it.local_id, sura, ayah) }
        addVerse(target.local_id, sura, ayah, at)
        return requireNotNull(membership(target.local_id, sura, ayah))
    }

    /** The highlight colors the verse has (one, unless a sync has yet to settle a concurrent change). */
    fun highlightColorsOf(sura: Int, ayah: Int): Set<AyahHighlightColor> {
        val colors = highlightCollections().entries.associate { (color, row) -> row.local_id to color }
        return membershipsOf(sura, ayah).mapNotNull { colors[it.collection_local_id] }.toSet()
    }

    /** Removes every highlight color of the verse; returns whether it had one. */
    fun clearHighlights(sura: Int, ayah: Int): Boolean =
        highlightCollections().values.map { removeVerse(it.local_id, sura, ayah) }.any { it }
}

/** Highlight collections (`system:highlights:<color>`), which the app changes only through the highlight API. */
internal val CollectionRow.isHighlight: Boolean get() = highlightColorForCollectionName(name) != null
