package com.quran.shared.persistence.repository.bookmark.repository

import com.quran.shared.persistence.model.BookmarkCollectionsReplacementResult
import com.quran.shared.persistence.util.PlatformDateTime
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines

interface BookmarksRepository {
    /**
     * Replaces an ayah bookmark's saved collection memberships exactly.
     *
     * Non-empty memberships create the bookmark if needed. Empty memberships remove the saved
     * bookmark, or do nothing when it does not exist. Highlight collection memberships are always
     * preserved and excluded from replacement.
     */
    @NativeCoroutines
    suspend fun replaceAyahBookmarkCollections(
        sura: Int,
        ayah: Int,
        collectionIds: List<String>
    ): BookmarkCollectionsReplacementResult

    /**
     * Replaces an ayah bookmark's saved collection memberships exactly with an explicit mutation
     * timestamp.
     *
     * Non-empty memberships create the bookmark if needed. Empty memberships remove the saved
     * bookmark, or do nothing when it does not exist. Highlight collection memberships are always
     * preserved and excluded from replacement.
     */
    @NativeCoroutines
    suspend fun replaceAyahBookmarkCollections(
        sura: Int,
        ayah: Int,
        collectionIds: List<String>,
        timestamp: PlatformDateTime
    ): BookmarkCollectionsReplacementResult
}
