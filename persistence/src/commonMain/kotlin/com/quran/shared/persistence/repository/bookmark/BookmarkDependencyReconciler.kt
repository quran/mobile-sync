package com.quran.shared.persistence.repository.bookmark

import com.quran.shared.persistence.QuranDatabase
import dev.zacsweers.metro.Inject

@Inject
class BookmarkDependencyReconciler(
    private val database: QuranDatabase
) {
    fun reconcile() {
        val bookmarkQueries = database.bookmarksQueries
        val linkQueries = database.bookmark_collectionsQueries

        linkQueries.markActiveLinksWithChangedSnapshots()
        linkQueries.deleteLinksForInactiveParents()
        linkQueries.markSyncedLinksForInactiveParents()
        linkQueries.deleteInactiveClearedLinks()

        val activeReadingRows = bookmarkQueries.getReadingBookmarks().executeAsList()
        activeReadingRows.drop(1).forEach { row ->
            bookmarkQueries.clearReadingWithoutPending(
                local_id = row.local_id,
                modified_at = activeReadingRows.first().reading_modified_at
                    ?: activeReadingRows.first().modified_at
            )
        }

        bookmarkQueries.deleteLocalOrphanBookmarks()
    }

    fun pruneBookmarkIfOrphan(bookmarkLocalId: Long) {
        val bookmarkQueries = database.bookmarksQueries
        val linkQueries = database.bookmark_collectionsQueries
        val row = bookmarkQueries.getBookmarkByLocalId(bookmarkLocalId).executeAsOneOrNull() ?: return
        linkQueries.deleteInactiveClearedLinks()
        linkQueries.deleteRetiredInactiveClearedLinksForBookmark(bookmark_local_id = bookmarkLocalId)
        val retainedLinks = linkQueries.countRetainedForBookmark(bookmarkLocalId).executeAsOne()
        val hasPendingFacet = row.bookmark_pending_op != null ||
            row.reading_pending_op != null ||
            row.default_pending_op != null

        if (row.is_reading == 0L &&
            row.is_in_default_collection == 0L &&
            retainedLinks == 0L &&
            !hasPendingFacet
        ) {
            bookmarkQueries.hardDeleteBookmarkByLocalId(bookmarkLocalId)
        }
    }
}
