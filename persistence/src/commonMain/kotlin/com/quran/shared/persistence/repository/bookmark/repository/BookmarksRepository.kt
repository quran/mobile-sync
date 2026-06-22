package com.quran.shared.persistence.repository.bookmark.repository

import com.quran.shared.persistence.model.AyahBookmark
import com.quran.shared.persistence.model.BookmarkCollectionsReplacementResult
import com.quran.shared.persistence.util.PlatformDateTime
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import kotlinx.coroutines.flow.Flow

interface BookmarksRepository {
    /**
     * Fetch and returns all bookmarks.
     *
     * @return List<AyahBookmark> the current list of bookmarks
     */
    @NativeCoroutines
    suspend fun getAllBookmarks(): List<AyahBookmark>

    /**
     * Returns a flow of all bookmarks for observation.
     */
    @NativeCoroutines
    fun getBookmarksFlow(): Flow<List<AyahBookmark>>

    /**
     * Add a bookmark for a given sura and ayah.
     *
     * @param sura the sura number
     * @param ayah the ayah number
     * @return the [AyahBookmark]
     */
    @NativeCoroutines
    suspend fun addBookmark(sura: Int, ayah: Int): AyahBookmark

    @NativeCoroutines
    suspend fun addBookmark(sura: Int, ayah: Int, timestamp: PlatformDateTime): AyahBookmark

    /**
     * Add a saved ayah bookmark and add it to the requested memberships.
     *
     * Null or empty memberships normalize to the virtual default collection. A non-empty list is
     * additive: requested memberships are added, while existing custom memberships not present in
     * the list are left unchanged. [com.quran.shared.persistence.model.DEFAULT_COLLECTION_ID]
     * represents default membership.
     */
    @NativeCoroutines
    suspend fun addBookmark(sura: Int, ayah: Int, collectionLocalIds: List<String>?): AyahBookmark

    @NativeCoroutines
    suspend fun addBookmark(
        sura: Int,
        ayah: Int,
        collectionLocalIds: List<String>?,
        timestamp: PlatformDateTime
    ): AyahBookmark

    /**
     * Replaces the saved collection memberships for an existing ayah bookmark.
     *
     * Null or empty memberships normalize to the virtual default collection. Use [deleteBookmark]
     * when a saved bookmark should be removed from every collection.
     *
     * @return `true` when memberships changed, or `false` when the bookmark is missing, deleted,
     * or already has exactly the requested memberships.
     */
    @NativeCoroutines
    suspend fun replaceBookmarkCollections(localId: String, collectionLocalIds: List<String>?): Boolean

    /**
     * Replaces the saved collection memberships for an existing ayah bookmark with an explicit
     * mutation timestamp.
     *
     * Null or empty memberships normalize to the virtual default collection. Use [deleteBookmark]
     * when a saved bookmark should be removed from every collection.
     *
     * @return `true` when memberships changed, or `false` when the bookmark is missing, deleted,
     * or already has exactly the requested memberships.
     */
    @NativeCoroutines
    suspend fun replaceBookmarkCollections(
        localId: String,
        collectionLocalIds: List<String>?,
        timestamp: PlatformDateTime
    ): Boolean

    /**
     * Creates an ayah bookmark if needed, then replaces its saved collection memberships exactly.
     *
     * Null or empty memberships normalize to the virtual default collection.
     */
    @NativeCoroutines
    suspend fun replaceAyahBookmarkCollections(
        sura: Int,
        ayah: Int,
        collectionLocalIds: List<String>?
    ): BookmarkCollectionsReplacementResult

    /**
     * Creates an ayah bookmark if needed, then replaces its saved collection memberships exactly
     * with an explicit mutation timestamp.
     *
     * Null or empty memberships normalize to the virtual default collection.
     */
    @NativeCoroutines
    suspend fun replaceAyahBookmarkCollections(
        sura: Int,
        ayah: Int,
        collectionLocalIds: List<String>?,
        timestamp: PlatformDateTime
    ): BookmarkCollectionsReplacementResult

    /**
     * Delete a bookmark for a specific sura and ayah, including any collection links.
     *
     * @return a boolean denoting success
     */
    @NativeCoroutines
    suspend fun deleteBookmark(sura: Int, ayah: Int): Boolean

    /**
     * Delete a bookmark, including any collection links.
     *
     * @return a boolean denoting success
     */
    @NativeCoroutines
    suspend fun deleteBookmark(bookmark: AyahBookmark): Boolean

    /**
     * Delete a bookmark by local ID, including any collection links.
     *
     * @return a boolean denoting success
     */
    @NativeCoroutines
    suspend fun deleteBookmark(localId: String): Boolean
}
