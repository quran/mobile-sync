package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

internal const val SUPPORTED_MUSHAF_ID = 1L

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
