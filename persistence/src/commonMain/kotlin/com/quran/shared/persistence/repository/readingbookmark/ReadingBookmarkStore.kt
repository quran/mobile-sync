package com.quran.shared.persistence.repository.readingbookmark

import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.model.DatabaseReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.persistence.model.SUPPORTED_MUSHAF_ID
import com.quran.shared.persistence.model.toStorageValue

/** Transaction-scoped access to persisted reading bookmarks. */
internal class ReadingBookmarkStore(
    private val database: QuranDatabase
) {
    private val queries = database.reading_bookmarksQueries

    fun setAyah(
        slot: ReadingBookmarkSlot,
        sura: Int,
        ayah: Int,
        timestampMillis: Long
    ): DatabaseReadingBookmark {
        val slotValue = slot.toStorageValue().toLong()
        queries.setAyahReadingBookmark(
            slot = slotValue,
            sura = sura.toLong(),
            ayah = ayah.toLong(),
            mushaf_id = SUPPORTED_MUSHAF_ID,
            timestamp = timestampMillis
        )
        return requireSlot(slotValue)
    }

    fun setPage(
        slot: ReadingBookmarkSlot,
        page: Int,
        timestampMillis: Long
    ): DatabaseReadingBookmark {
        val slotValue = slot.toStorageValue().toLong()
        queries.setPageReadingBookmark(
            slot = slotValue,
            page = page.toLong(),
            mushaf_id = SUPPORTED_MUSHAF_ID,
            timestamp = timestampMillis
        )
        return requireSlot(slotValue)
    }

    fun rename(
        slot: ReadingBookmarkSlot,
        name: String?,
        timestampMillis: Long
    ): DatabaseReadingBookmark {
        val slotValue = slot.toStorageValue().toLong()
        queries.renameReadingBookmark(slotValue, name, timestampMillis)
        return requireSlot(slotValue)
    }

    fun clear(slot: ReadingBookmarkSlot, timestampMillis: Long): DatabaseReadingBookmark {
        val slotValue = slot.toStorageValue().toLong()
        queries.clearReadingBookmark(slotValue, timestampMillis)
        return requireSlot(slotValue)
    }

    private fun requireSlot(slot: Long): DatabaseReadingBookmark =
        requireNotNull(queries.getReadingBookmarkForSlot(slot).executeAsOneOrNull()) {
            "Expected reading bookmark for slot=$slot after mutation."
        }
}
