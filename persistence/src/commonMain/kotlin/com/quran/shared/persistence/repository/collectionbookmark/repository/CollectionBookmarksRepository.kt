package com.quran.shared.persistence.repository.collectionbookmark.repository

import com.quran.shared.persistence.model.AyahHighlight
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.CollectionAyahBookmark
import com.quran.shared.persistence.util.PlatformDateTime
import kotlinx.coroutines.flow.Flow

interface CollectionBookmarksRepository {
    /**
     * Observe the current highlight for every highlighted ayah.
     */
    fun getHighlightsFlow(): Flow<List<AyahHighlight>>

    /**
     * Sets the highlight color for one ayah, replacing any existing highlight color.
     * Default and user collection memberships are preserved.
     */
    suspend fun setHighlight(
        sura: Int,
        ayah: Int,
        color: AyahHighlightColor,
        timestamp: PlatformDateTime
    ): AyahHighlight

    /**
     * Deletes the highlight from one ayah by removing all its highlight memberships, using the
     * current platform time.
     * Default and user collection memberships are preserved.
     *
     * @param sura the sura number containing the ayah.
     * @param ayah the ayah number whose highlight should be deleted.
     * @return `true` when at least one highlight membership was removed; otherwise `false`.
     */
    suspend fun deleteHighlight(sura: Int, ayah: Int): Boolean

    /**
     * Deletes the highlight from one ayah by removing all its highlight memberships at an
     * explicit mutation time.
     * The timestamp is stored on any deletion mutation so it can be synchronized. Default and
     * user collection memberships are preserved.
     *
     * @param sura the sura number containing the ayah.
     * @param ayah the ayah number whose highlight should be deleted.
     * @param timestamp the time to record for the deletion mutation.
     * @return `true` when at least one highlight membership was removed; otherwise `false`.
     */
    suspend fun deleteHighlight(sura: Int, ayah: Int, timestamp: PlatformDateTime): Boolean

    /**
     * Deletes every ayah's [color] highlight in one transaction, by removing the memberships of the color's highlight
     * collection. The collection itself stays, as it does when [deleteHighlight] removes its last verse. Other colors
     * and default and user collection memberships are preserved. A stored verse that doesn't exist (such as 2:999) is
     * kept and not counted: the app never shows it, and the server would refuse its delete.
     *
     * @param color the highlight color to clear.
     * @return how many highlight memberships were removed; `0` when no existing ayah had [color].
     */
    suspend fun deleteHighlights(color: AyahHighlightColor): Int

    /**
     * Returns all bookmarks linked to a collection.
     */
    suspend fun getBookmarksForCollection(collectionId: String): List<CollectionAyahBookmark>

    /**
     * Atomically creates an ayah bookmark (if missing) and links it to an active saved collection.
     * Highlight collections must be changed through [setHighlight]. This operation must not leave
     * partial state if linking fails, and later saved memberships do not rewrite the bookmark time.
     */
    suspend fun addAyahBookmarkToCollection(
        collectionId: String,
        sura: Int,
        ayah: Int
    ): CollectionAyahBookmark

    /**
     * Links an ayah to an active saved collection using [timestamp] for a new bookmark or its first
     * saved membership. Highlight collections must be changed through [setHighlight].
     */
    suspend fun addAyahBookmarkToCollection(
        collectionId: String,
        sura: Int,
        ayah: Int,
        timestamp: PlatformDateTime
    ): CollectionAyahBookmark

    /**
     * Removes the ayah bookmark [bookmarkId] from the collection [collectionId].
     *
     * @return whether an active membership was removed.
     */
    suspend fun removeAyahBookmarkFromCollection(collectionId: String, bookmarkId: String): Boolean

    /**
     * Removes every ayah bookmark from the collection [collectionId] in one transaction, keeping the collection. Like
     * [removeAyahBookmarkFromCollection], it works on read-only collections such as the default collection (read-only
     * only blocks renaming and deleting a collection). Highlight collections are rejected, as in
     * [addAyahBookmarkToCollection]: they change through [deleteHighlights]. The verses' memberships in other
     * collections are preserved. A stored verse that doesn't exist (such as 2:999) is kept and not counted: the app
     * never shows it, and the server would refuse its delete.
     *
     * @return how many memberships were removed; `0` when the collection has no existing verse or [collectionId] names
     *   none.
     * @throws IllegalArgumentException when [collectionId] is a highlight collection.
     */
    suspend fun removeAllAyahBookmarksFromCollection(collectionId: String): Int

    /**
     * Observe the bookmarks for a collection as a Flow.
     */
    fun getBookmarksForCollectionFlow(collectionId: String): Flow<List<CollectionAyahBookmark>>
}
