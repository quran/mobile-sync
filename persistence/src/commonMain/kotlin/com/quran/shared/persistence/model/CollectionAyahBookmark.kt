package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

/**
 * App-facing ayah bookmark membership in a collection.
 *
 * @param collectionId the id of the collection
 * @param bookmarkId the id of the bookmark
 * @param sura the sura number
 * @param ayah the ayah number
 * @param bookmarkLastUpdated the last time the bookmark itself changed
 * @param bookmarkAddedDate the time the bookmark itself was created locally
 */
data class CollectionAyahBookmark(
    val collectionId: String,
    val bookmarkId: String,
    val sura: Int,
    val ayah: Int,
    val bookmarkLastUpdated: PlatformDateTime,
    val bookmarkAddedDate: PlatformDateTime
)
