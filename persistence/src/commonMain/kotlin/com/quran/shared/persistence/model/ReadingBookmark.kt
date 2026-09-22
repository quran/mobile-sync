package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

internal const val SUPPORTED_MUSHAF_ID = 1L

sealed interface ReadingBookmark {
    val slot: ReadingBookmarkSlot
    val name: String?
    val lastUpdated: PlatformDateTime
    val id: String
}

/** Reading bookmark colors with stable storage and sync IDs: green = 1, purple = 2, blue = 3. */
enum class ReadingBookmarkSlot {
    GREEN,
    PURPLE,
    BLUE
}

internal fun ReadingBookmarkSlot.toStorageValue(): Int = when (this) {
    ReadingBookmarkSlot.GREEN -> 1
    ReadingBookmarkSlot.PURPLE -> 2
    ReadingBookmarkSlot.BLUE -> 3
}

internal fun Int.toReadingBookmarkSlot(): ReadingBookmarkSlot = when (this) {
    1 -> ReadingBookmarkSlot.GREEN
    2 -> ReadingBookmarkSlot.PURPLE
    3 -> ReadingBookmarkSlot.BLUE
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

data class PageReadingBookmark(
    val page: Int,
    override val lastUpdated: PlatformDateTime,
    override val id: String,
    override val slot: ReadingBookmarkSlot,
    override val name: String? = null
) : ReadingBookmark

data class EmptyReadingBookmark(
    override val slot: ReadingBookmarkSlot,
    override val name: String?,
    override val lastUpdated: PlatformDateTime,
    override val id: String
) : ReadingBookmark
