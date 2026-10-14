package com.quran.shared.persistence.repository.readingbookmark

import com.quran.shared.mutations.trimLikeServer
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.persistence.model.toStorageValue
import com.quran.shared.syncv2.db.Reading_bookmark
import com.quran.shared.syncv2.db.SyncV2Database
import com.quran.shared.syncv2.types.ReadingLocation

/**
 * The one write path for reading bookmarks: one row per slot, which a clear keeps (with its name and the server's
 * metadata). Names are stored trimmed like the server, an empty one as null. Callers run writes in a transaction.
 */
internal class ReadingBookmarkStore(private val database: SyncV2Database) {
    private val queries get() = database.readingBookmarksQueries

    fun all(): List<Reading_bookmark> = queries.all().executeAsList()

    fun get(slot: ReadingBookmarkSlot): Reading_bookmark? = queries.bySlot(slot.storage).executeAsOneOrNull()

    fun setLocation(slot: ReadingBookmarkSlot, location: ReadingLocation?, at: Long): Reading_bookmark =
        write(slot, at) { it.copy(location = location) }

    fun rename(slot: ReadingBookmarkSlot, name: String?, at: Long): Reading_bookmark =
        write(slot, at) { it.copy(name = name?.trimLikeServer()?.ifEmpty { null }) }

    private fun write(slot: ReadingBookmarkSlot, at: Long, change: (Fields) -> Fields): Reading_bookmark {
        val existing = get(slot)
        val next = change(existing?.fields() ?: Fields(name = null, location = null))
        val location = next.location
        if (existing == null) {
            queries.insert(
                slot.storage, next.name, location?.type, location?.key?.toLong(), location?.verse?.toLong(),
                location?.mushafId?.toLong(), null, at, at
            )
        } else {
            queries.update(
                slot.storage, next.name, location?.type, location?.key?.toLong(), location?.verse?.toLong(),
                location?.mushafId?.toLong(), existing.metadata, existing.created_at, at, existing.local_id
            )
        }
        return requireNotNull(get(slot)) { "Expected reading bookmark for slot=$slot after mutation." }
    }

    private data class Fields(val name: String?, val location: ReadingLocation?)

    private fun Reading_bookmark.fields() = Fields(name, location())

    private val ReadingBookmarkSlot.storage: Long get() = toStorageValue().toLong()
}

internal fun Reading_bookmark.location(): ReadingLocation? = location_type?.let { type ->
    ReadingLocation.of(type, location_key!!.toInt(), location_verse?.toInt(), mushaf_id?.toInt() ?: ReadingLocation.STANDARD)
}
