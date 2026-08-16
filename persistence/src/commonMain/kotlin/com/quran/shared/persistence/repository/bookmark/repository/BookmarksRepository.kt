package com.quran.shared.persistence.repository.bookmark.repository

import com.quran.shared.persistence.model.BookmarkCollectionsReplacementResult
import com.quran.shared.persistence.util.PlatformDateTime
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines

interface BookmarksRepository {
    /**
     * Creates an ayah bookmark if needed, then replaces its saved collection memberships exactly.
     * Empty memberships normalize to the virtual default collection.
     */
    @NativeCoroutines
    suspend fun replaceAyahBookmarkCollections(
        sura: Int,
        ayah: Int,
        collectionIds: List<String>
    ): BookmarkCollectionsReplacementResult

    /**
     * Creates an ayah bookmark if needed, then replaces its saved collection memberships exactly
     * with an explicit mutation timestamp.
     * Empty memberships normalize to the virtual default collection.
     */
    @NativeCoroutines
    suspend fun replaceAyahBookmarkCollections(
        sura: Int,
        ayah: Int,
        collectionIds: List<String>,
        timestamp: PlatformDateTime
    ): BookmarkCollectionsReplacementResult
}
