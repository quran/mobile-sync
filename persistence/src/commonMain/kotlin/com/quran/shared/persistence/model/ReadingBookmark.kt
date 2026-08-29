package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

sealed interface ReadingBookmark {
    val slot: Int
    val name: String?
    val lastUpdated: PlatformDateTime
    val id: String
}

data class AyahReadingBookmark(
    val sura: Int,
    val ayah: Int,
    override val lastUpdated: PlatformDateTime,
    override val id: String,
    override val slot: Int,
    override val name: String? = null
) : ReadingBookmark

data class PageReadingBookmark(
    val page: Int,
    override val lastUpdated: PlatformDateTime,
    override val id: String,
    override val slot: Int,
    override val name: String? = null
) : ReadingBookmark

data class EmptyReadingBookmark(
    override val slot: Int,
    override val name: String?,
    override val lastUpdated: PlatformDateTime,
    override val id: String
) : ReadingBookmark
