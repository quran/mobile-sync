package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

/**
 * App-facing saved ayah bookmark.
 *
 * @param sura the sura number
 * @param ayah the ayah number
 * @param id the identifier for the ayah bookmark
 * @param lastUpdated when the bookmark was last updated
 * @param addedDate when the bookmark was first added
 */
data class AyahBookmark(
    val sura: Int,
    val ayah: Int,
    val id: String,
    val lastUpdated: PlatformDateTime,
    val addedDate: PlatformDateTime = lastUpdated
)
