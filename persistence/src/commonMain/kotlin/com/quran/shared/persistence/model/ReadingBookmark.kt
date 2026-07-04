package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

/**
 * App-facing current reading bookmark.
 */
sealed interface ReadingBookmark {
    val lastUpdated: PlatformDateTime
    val id: String
}

/**
 * Ayah Reading Bookmark
 *
 * @param sura the sura
 * @parma ayah the ayah
 * @param lastUpdated the last updated
 * @param id the identifier of the ayah reading bookmark
 */
data class AyahReadingBookmark(
    val sura: Int,
    val ayah: Int,
    override val lastUpdated: PlatformDateTime,
    override val id: String
) : ReadingBookmark

/**
 * Page Reading Bookmark
 *
 * @param page the page
 * @param lastUpdated the last updated time of the ayah reading bookmark
 * @param id the identifier of the page reading bookmark
 */
data class PageReadingBookmark(
    val page: Int,
    override val lastUpdated: PlatformDateTime,
    override val id: String
) : ReadingBookmark
