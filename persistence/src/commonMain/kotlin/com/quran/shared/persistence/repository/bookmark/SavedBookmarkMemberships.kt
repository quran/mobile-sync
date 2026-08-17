package com.quran.shared.persistence.repository.bookmark

import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.model.highlightColorForCollectionName

/**
 * Returns the active non-highlight collection IDs that make a bookmark app-facing saved content.
 *
 * Callers must invoke this from the database dispatcher and, when used to decide a write, from the
 * same transaction as that write so the empty-to-nonempty transition cannot race another mutation.
 *
 * @param bookmarkLocalId local bookmark row whose saved memberships should be inspected.
 */
internal fun QuranDatabase.activeSavedCollectionIdsForBookmark(bookmarkLocalId: Long): Set<Long> {
    val bookmark = bookmarksQueries.getBookmarkByLocalId(bookmarkLocalId).executeAsOneOrNull()
    if (bookmark?.deleted != 0L) {
        return emptySet()
    }
    return bookmark_collectionsQueries
        .getActiveCollectionLocalIdsForBookmark(bookmarkLocalId)
        .executeAsList()
        .mapNotNull { collectionLocalId ->
            val collection = collectionsQueries
                .getCollectionByLocalId(collectionLocalId)
                .executeAsOne()
            collectionLocalId.takeIf {
                collection.deleted == 0L && highlightColorForCollectionName(collection.name) == null
            }
        }
        .toSet()
}
