package com.quran.shared.persistence.repository

import com.quran.shared.persistence.model.Bookmark
import kotlinx.coroutines.flow.Flow

class DuplicateBookmarkException(message: String) : Exception(message)
class BookmarkNotFoundException(message: String) : Exception(message)

interface BookmarksRepository {

    // region Basic Operations
    /**
     * Returns a Flow of all bookmarks, including both local and remote bookmarks.
     * The Flow will emit new values whenever bookmarks are added, deleted, or synchronized.
     */
    fun getAllBookmarks(): Flow<List<Bookmark>>

    /**
     * Adds a bookmark for a specific page.
     * @throws DuplicateBookmarkException if a bookmark for this page already exists
     */
    suspend fun addPageBookmark(page: Int)

    /**
     * Adds a bookmark for a specific ayah.
     * @throws DuplicateBookmarkException if a bookmark for this ayah already exists
     */
    suspend fun addAyahBookmark(sura: Int, ayah: Int)

    /**
     * Deletes a bookmark for a specific page.
     * @throws BookmarkNotFoundException if no bookmark exists for this page
     */
    suspend fun deletePageBookmark(page: Int)

    /**
     * Deletes a bookmark for a specific ayah.
     * @throws BookmarkNotFoundException if no bookmark exists for this ayah
     */
    suspend fun deleteAyahBookmark(sura: Int, ayah: Int)
    // endregion

    // region Synchronization
    /**
     * Returns a list of bookmarks that have been mutated locally (created or deleted)
     * that need to be synchronized with remote storage.
     */
    suspend fun fetchMutatedBookmarks(): List<Bookmark>

    /**
     * Persists updates from remote storage to local storage. 
     *
     * The mutation property is used to decide whether to insert or delete a record.
     *
     * @param mutations List of bookmarks with their remote IDs and mutation states
     * @throws IllegalArgumentException if any bookmark has no remote ID
     */
    suspend fun persistRemoteUpdates(mutations: List<Bookmark>)

    /**
     * Clears all local mutations, typically called after successful synchronization.
     */
    suspend fun clearLocalMutations()
    // endregion

    // region Migration
    /**
     * Migrates existing bookmarks to the new storage format.
     * 
     * This method should only be called during app initialization, before any remote 
     * bookmarks are added and before any changes by the user are handled. 
     *
     * @param bookmarks List of bookmarks to migrate
     * @throws IllegalStateException if either bookmarks or mutations tables are not empty
     * @throws IllegalArgumentException if any bookmark has a remote ID or is marked as deleted
     */
    suspend fun migrateBookmarks(bookmarks: List<Bookmark>)
    // endregion
}