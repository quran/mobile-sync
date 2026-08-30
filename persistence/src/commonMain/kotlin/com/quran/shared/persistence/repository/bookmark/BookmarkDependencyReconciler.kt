package com.quran.shared.persistence.repository.bookmark

import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.util.currentEpochMilliseconds
import dev.zacsweers.metro.Inject

@Inject
class BookmarkDependencyReconciler(
    private val database: QuranDatabase
) {
    fun reconcile(timestampMillis: Long = currentEpochMilliseconds()) {
        val bookmarkQueries = database.bookmarksQueries
        val linkQueries = database.bookmark_collectionsQueries

        linkQueries.markActiveLinksWithChangedSnapshots(modified_at = timestampMillis)
        linkQueries.deleteLinksForInactiveParents()
        linkQueries.markSyncedLinksForInactiveParents(modified_at = timestampMillis)
        linkQueries.deleteInactiveClearedLinks()

        bookmarkQueries.deleteLocalOrphanBookmarks()
    }

    fun pruneBookmarkIfOrphan(bookmarkLocalId: Long) {
        val bookmarkQueries = database.bookmarksQueries
        val linkQueries = database.bookmark_collectionsQueries
        val row = bookmarkQueries.getBookmarkByLocalId(bookmarkLocalId).executeAsOneOrNull() ?: return
        linkQueries.deleteInactiveClearedLinks()
        linkQueries.deleteRetiredInactiveClearedLinksForBookmark(bookmark_local_id = bookmarkLocalId)
        val retainedLinks = linkQueries.countRetainedForBookmark(bookmarkLocalId).executeAsOne()
        val hasPendingFacet = row.bookmark_pending_op != null

        if (retainedLinks == 0L && !hasPendingFacet) {
            bookmarkQueries.hardDeleteBookmarkByLocalId(bookmarkLocalId)
        }
    }
}
