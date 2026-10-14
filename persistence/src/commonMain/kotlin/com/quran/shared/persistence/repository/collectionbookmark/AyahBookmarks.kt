package com.quran.shared.persistence.repository.collectionbookmark

import com.quran.shared.persistence.model.AyahBookmark
import com.quran.shared.persistence.model.CollectionAyahBookmark
import com.quran.shared.persistence.util.toPlatform
import com.quran.shared.syncv2.db.Collection_ayah
import kotlin.time.Instant

/**
 * An ayah bookmark is not a row of its own: it is a verse in one or more collections. Its id is the verse
 * (`sura:ayah`), and its dates come from its memberships: [addedAt] is the earliest of all of them (a highlight can
 * come first), [lastUpdatedAt] the earliest saved one, so later saves don't move it.
 */
internal class AyahBookmarkDates(memberships: List<Collection_ayah>, highlightCollections: Set<Long>) {
    private val byVerse = memberships.groupBy { it.sura.toInt() to it.ayah.toInt() }
    private val highlights = highlightCollections

    fun addedAt(sura: Int, ayah: Int): Long = byVerse[sura to ayah].orEmpty().minOfOrNull { it.created_at } ?: 0L

    fun lastUpdatedAt(sura: Int, ayah: Int): Long =
        byVerse[sura to ayah].orEmpty().filter { it.collection_local_id !in highlights }.minOfOrNull { it.created_at }
            ?: addedAt(sura, ayah)

    fun ayahBookmark(sura: Int, ayah: Int) = AyahBookmark(
        sura = sura,
        ayah = ayah,
        id = ayahBookmarkId(sura, ayah),
        lastUpdated = Instant.fromEpochMilliseconds(lastUpdatedAt(sura, ayah)).toPlatform(),
        addedDate = Instant.fromEpochMilliseconds(addedAt(sura, ayah)).toPlatform()
    )

    fun collectionAyahBookmark(membership: Collection_ayah): CollectionAyahBookmark {
        val sura = membership.sura.toInt()
        val ayah = membership.ayah.toInt()
        return CollectionAyahBookmark(
            collectionId = membership.collection_local_id.toString(),
            bookmarkId = ayahBookmarkId(sura, ayah),
            sura = sura,
            ayah = ayah,
            bookmarkLastUpdated = Instant.fromEpochMilliseconds(lastUpdatedAt(sura, ayah)).toPlatform(),
            bookmarkAddedDate = Instant.fromEpochMilliseconds(addedAt(sura, ayah)).toPlatform()
        )
    }
}

internal fun ayahBookmarkId(sura: Int, ayah: Int): String = "$sura:$ayah"

/** The verse of an ayah bookmark id; an id that isn't one names no bookmark. */
internal fun parseAyahBookmarkId(id: String): Pair<Int, Int>? {
    val parts = id.split(":")
    if (parts.size != 2) return null
    val sura = parts[0].toIntOrNull() ?: return null
    val ayah = parts[1].toIntOrNull() ?: return null
    return sura to ayah
}
