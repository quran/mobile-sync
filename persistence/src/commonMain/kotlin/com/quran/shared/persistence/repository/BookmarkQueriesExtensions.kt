package com.quran.shared.persistence.repository

import com.quran.shared.persistence.Bookmarks
import com.quran.shared.persistence.Bookmarks_mutations
import com.quran.shared.persistence.GetAyahBookmarkMutations
import com.quran.shared.persistence.GetAyahBookmarks
import com.quran.shared.persistence.GetPageBookmarkMutations
import com.quran.shared.persistence.GetPageBookmarks
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.model.BookmarkMutation
import com.quran.shared.persistence.model.BookmarkMutationType

fun Bookmarks_mutations.toBookmark(): Bookmark {
    return if (page != null) {
        Bookmark.PageBookmark(
            page = page.toInt(),
            remoteId = remote_id,
            lastUpdated = created_at
        )
    } else {
        Bookmark.AyahBookmark(
            sura = sura!!.toInt(),
            ayah = ayah!!.toInt(),
            remoteId = remote_id,
            lastUpdated = created_at
        )
    }
}

fun Bookmarks_mutations.toBookmarkMutation(): BookmarkMutation = BookmarkMutation(
    page = page?.toInt(),
    sura = sura?.toInt(),
    ayah = ayah?.toInt(),
    remoteId = remote_id,
    mutationType = deleted.toMutationType(),
    lastUpdated = created_at
)

fun Bookmarks.toBookmark(): Bookmark {
    return if (page != null) {
        Bookmark.PageBookmark(
            page = page.toInt(),
            remoteId = remote_id,
            lastUpdated = created_at
        )
    } else {
        Bookmark.AyahBookmark(
            sura = sura!!.toInt(),
            ayah = ayah!!.toInt(),
            remoteId = remote_id,
            lastUpdated = created_at
        )
    }
}

fun GetPageBookmarks.toPageBookmark(): Bookmark.PageBookmark = Bookmark.PageBookmark(
    page = page.toInt(),
    remoteId = remote_id,
    lastUpdated = created_at
)

fun GetAyahBookmarks.toAyahBookmark(): Bookmark.AyahBookmark = Bookmark.AyahBookmark(
    ayah = ayah.toInt(),
    sura = sura.toInt(),
    remoteId = remote_id,
    lastUpdated = created_at
)

fun GetPageBookmarkMutations.toBookmarkMutation(): BookmarkMutation = BookmarkMutation(
    page = page.toInt(),
    remoteId = remote_id,
    mutationType = deleted.toMutationType(),
    lastUpdated = created_at
)

fun GetAyahBookmarkMutations.toBookmarkMutation(): BookmarkMutation = BookmarkMutation(
    sura = sura.toInt(),
    ayah = ayah.toInt(),
    remoteId = remote_id,
    mutationType = deleted.toMutationType(),
    lastUpdated = created_at
)

private fun Long.toMutationType(): BookmarkMutationType = if (this == 0L) BookmarkMutationType.CREATED else BookmarkMutationType.DELETED

fun BookmarkMutation.toBookmark(): Bookmark? = when {
    page != null -> Bookmark.PageBookmark(
        page = page,
        remoteId = remoteId,
        lastUpdated = lastUpdated
    )
    sura != null && ayah != null -> Bookmark.AyahBookmark(
        sura = sura,
        ayah = ayah,
        remoteId = remoteId,
        lastUpdated = lastUpdated
    )
    else -> null
}

fun BookmarkMutation.toPageBookmark(): Bookmark.PageBookmark? = if (page != null) {
    Bookmark.PageBookmark(
        page = page,
        remoteId = remoteId,
        lastUpdated = lastUpdated
    )
} else null

fun BookmarkMutation.toAyahBookmark(): Bookmark.AyahBookmark? = if (sura != null && ayah != null) {
    Bookmark.AyahBookmark(
        sura = sura,
        ayah = ayah,
        remoteId = remoteId,
        lastUpdated = lastUpdated
    )
} else null