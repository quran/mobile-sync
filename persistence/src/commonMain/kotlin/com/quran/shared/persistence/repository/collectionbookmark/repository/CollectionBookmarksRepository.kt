package com.quran.shared.persistence.repository.collectionbookmark.repository

import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.model.CollectionBookmark

interface CollectionBookmarksRepository {
    /**
     * Returns all bookmarks linked to a collection.
     */
    suspend fun getBookmarksForCollection(collectionLocalId: String): List<CollectionBookmark>

    /**
     * Adds a bookmark to a collection locally.
     */
    suspend fun addBookmarkToCollection(collectionLocalId: String, bookmark: Bookmark): CollectionBookmark

    /**
     * Removes a bookmark from a collection locally.
     */
    suspend fun removeBookmarkFromCollection(collectionLocalId: String, bookmark: Bookmark): Boolean
}
