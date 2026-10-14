package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

sealed interface ReadingBookmark {
    val slot: ReadingBookmarkSlot
    val name: String?
    val lastUpdated: PlatformDateTime
    val id: String
}

/** Reading bookmark colors with stable storage and sync IDs: teal = 1, orange = 2, red = 3. */
enum class ReadingBookmarkSlot {
    TEAL,
    ORANGE,
    RED
}

internal fun ReadingBookmarkSlot.toStorageValue(): Int = when (this) {
    ReadingBookmarkSlot.TEAL -> 1
    ReadingBookmarkSlot.ORANGE -> 2
    ReadingBookmarkSlot.RED -> 3
}

internal fun Int.toReadingBookmarkSlot(): ReadingBookmarkSlot = when (this) {
    1 -> ReadingBookmarkSlot.TEAL
    2 -> ReadingBookmarkSlot.ORANGE
    3 -> ReadingBookmarkSlot.RED
    else -> error("Unsupported reading bookmark slot: $this")
}

data class AyahReadingBookmark(
    val sura: Int,
    val ayah: Int,
    override val lastUpdated: PlatformDateTime,
    override val id: String,
    override val slot: ReadingBookmarkSlot,
    override val name: String? = null
) : ReadingBookmark

/**
 * A page in the mushaf [mushafId] numbers it in: the server keeps a page group, and answers with the group's mushaf.
 * 5 is the 604-page Madani mushaf (the default, and the one this app sets), 6 the 610-page and 7 the 548-page IndoPak.
 */
data class PageReadingBookmark(
    val page: Int,
    override val lastUpdated: PlatformDateTime,
    override val id: String,
    override val slot: ReadingBookmarkSlot,
    override val name: String? = null,
    val mushafId: Int = MADANI_MUSHAF_ID
) : ReadingBookmark {
    companion object {
        /** The 604-page Madani mushaf, the one page bookmarks set from this library use. */
        const val MADANI_MUSHAF_ID = 5
    }
}

data class EmptyReadingBookmark(
    override val slot: ReadingBookmarkSlot,
    override val name: String?,
    override val lastUpdated: PlatformDateTime,
    override val id: String
) : ReadingBookmark
