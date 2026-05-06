package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

sealed interface ReadingBookmark {
    val lastUpdated: PlatformDateTime
    val localId: String
}

data class AyahReadingBookmark(
    val sura: Int,
    val ayah: Int,
    override val lastUpdated: PlatformDateTime,
    override val localId: String
) : ReadingBookmark

data class PageReadingBookmark(
    val page: Int,
    override val lastUpdated: PlatformDateTime,
    override val localId: String
) : ReadingBookmark
