package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

sealed interface ReadingBookmark {
    val slot: ReadingBookmarkSlot
    val name: String?
    val lastUpdated: PlatformDateTime
    val id: String
}

enum class ReadingBookmarkSlot {
    CORAL,
    TEAL,
    INDIGO
}

internal fun ReadingBookmarkSlot.toStorageValue(): Int = when (this) {
    ReadingBookmarkSlot.CORAL -> 1
    ReadingBookmarkSlot.TEAL -> 2
    ReadingBookmarkSlot.INDIGO -> 3
}

internal fun Int.toReadingBookmarkSlot(): ReadingBookmarkSlot = when (this) {
    1 -> ReadingBookmarkSlot.CORAL
    2 -> ReadingBookmarkSlot.TEAL
    3 -> ReadingBookmarkSlot.INDIGO
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
