package com.quran.shared.persistence.repository

import com.quran.shared.persistence.Bookmarks
import com.quran.shared.persistence.Bookmarks_mutations
import com.quran.shared.persistence.GetAyahBookmarkMutations
import com.quran.shared.persistence.GetAyahBookmarks
import com.quran.shared.persistence.GetPageBookmarkMutations
import com.quran.shared.persistence.GetPageBookmarks
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.model.BookmarkLocalMutation

fun Bookmarks_mutations.toBookmark(): Bookmark {
    return if (page != null) {
        Bookmark.PageBookmark(
            page = page.toInt(),
            remoteId = remote_id,
            localMutation = deleted.toMutation(),
            lastUpdated = created_at
        )
    } else {
        Bookmark.AyahBookmark(
            sura = sura!!.toInt(),
            ayah = ayah!!.toInt(),
            remoteId = remote_id,
            localMutation = deleted.toMutation(),
            lastUpdated = created_at
        )
    }
}

fun Bookmarks.toBookmark(): Bookmark {
    return if (page != null) {
        Bookmark.PageBookmark(
            page = page.toInt(),
            remoteId = remote_id,
            localMutation = BookmarkLocalMutation.NONE,
            lastUpdated = created_at
        )
    } else {
        Bookmark.AyahBookmark(
            sura = sura!!.toInt(),
            ayah = ayah!!.toInt(),
            remoteId = remote_id,
            localMutation = BookmarkLocalMutation.NONE,
            lastUpdated = created_at
        )
    }
}

fun GetPageBookmarks.toPageBookmark(): Bookmark.PageBookmark = Bookmark.PageBookmark(
    page = page.toInt(),
    remoteId = remote_id,
    lastUpdated = created_at,
    localMutation = BookmarkLocalMutation.NONE
)

fun GetAyahBookmarks.toAyahBookmark(): Bookmark.AyahBookmark = Bookmark.AyahBookmark(
    ayah = ayah.toInt(),
    sura = sura.toInt(),
    remoteId = remote_id,
    localMutation = BookmarkLocalMutation.NONE,
    lastUpdated = created_at
)

fun GetPageBookmarkMutations.toPageBookmark(): Bookmark.PageBookmark = Bookmark.PageBookmark(
    page = page.toInt(),
    remoteId = remote_id,
    lastUpdated = created_at,
    localMutation = deleted.toMutation()
)

fun GetAyahBookmarkMutations.toAyahBookmark(): Bookmark.AyahBookmark = Bookmark.AyahBookmark(
    ayah = ayah.toInt(),
    sura = sura.toInt(),
    remoteId = remote_id,
    localMutation = deleted.toMutation(),
    lastUpdated = created_at
)

private fun Long.toMutation(): BookmarkLocalMutation = if (this == 0L) BookmarkLocalMutation.CREATED else BookmarkLocalMutation.DELETED