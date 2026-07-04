package com.quran.shared.persistence.repository.collectionbookmark.repository

import com.quran.shared.persistence.model.AyahBookmark
import com.quran.shared.persistence.model.CollectionAyahBookmark
import com.quran.shared.persistence.util.PlatformDateTime
import kotlinx.coroutines.flow.Flow

interface CollectionBookmarksRepository {
    /**
     * Returns all bookmarks linked to a collection.
     */
    suspend fun getBookmarksForCollection(collectionId: String): List<CollectionAyahBookmark>

    /**
     * Adds a bookmark to a collection locally.
     */
    suspend fun addBookmarkToCollection(collectionId: String, bookmark: AyahBookmark): CollectionAyahBookmark

    suspend fun addBookmarkToCollection(
        collectionId: String,
        bookmark: AyahBookmark,
        timestamp: PlatformDateTime
    ): CollectionAyahBookmark

    /**
     * Atomically creates an ayah bookmark (if missing) and links it to a collection.
     * This operation must not leave partial state if linking fails.
     */
    suspend fun addAyahBookmarkToCollection(
        collectionId: String,
        sura: Int,
        ayah: Int
    ): CollectionAyahBookmark

    suspend fun addAyahBookmarkToCollection(
        collectionId: String,
        sura: Int,
        ayah: Int,
        timestamp: PlatformDateTime
    ): CollectionAyahBookmark

    /**
     * Removes a bookmark from a collection locally.
     */
    suspend fun removeBookmarkFromCollection(collectionId: String, bookmark: AyahBookmark): Boolean

    suspend fun removeAyahBookmarkFromCollection(collectionAyahBookmark: CollectionAyahBookmark): Boolean

    /**
     * Observe the bookmarks for a collection as a Flow.
     */
    fun getBookmarksForCollectionFlow(collectionId: String): Flow<List<CollectionAyahBookmark>>
}
