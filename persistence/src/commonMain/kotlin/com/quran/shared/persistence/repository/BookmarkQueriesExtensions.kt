package com.quran.shared.persistence.repository

import com.quran.shared.persistence.GetAyahBookmarks
import com.quran.shared.persistence.GetPageBookmarks
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.model.BookmarkMutation
import com.quran.shared.persistence.model.BookmarkMutationType
import com.quran.shared.persistence.model.DatabaseBookmark

fun DatabaseBookmark.toBookmark(): Bookmark {
    return if (page != null) {
        Bookmark.PageBookmark(
            page = page.toInt(),
            remoteId = remote_id,
            lastUpdated = created_at
        )
    } else if (ayah != null && sura != null) {
        Bookmark.AyahBookmark(
            sura = sura.toInt(),
            ayah = ayah.toInt(),
            remoteId = remote_id,
            lastUpdated = created_at
        )
    } else {
        error("Unexpected bookmark's state: page, ayah and sura are all null.")
    }
}

fun DatabaseBookmark.toBookmarkMutation(): BookmarkMutation = BookmarkMutation(
    page = page?.toInt(),
    sura = sura?.toInt(),
    ayah = ayah?.toInt(),
    remoteId = remote_id,
    mutationType = if (deleted == 1L) BookmarkMutationType.DELETED else BookmarkMutationType.CREATED,
    lastUpdated = created_at
)

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