package com.quran.shared.persistence.model

/**
 * UI State model representing a collection along with its bookmarks.
 */
data class CollectionWithAyahBookmarks(
    val collection: Collection,
    val bookmarks: List<CollectionAyahBookmark>
)
