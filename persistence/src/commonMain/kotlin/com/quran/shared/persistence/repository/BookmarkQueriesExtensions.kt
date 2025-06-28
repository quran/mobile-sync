package com.quran.shared.persistence.repository

import com.quran.shared.persistence.model.PageBookmark
import com.quran.shared.persistence.model.BookmarkMutation
import com.quran.shared.persistence.model.BookmarkMutationType
import com.quran.shared.persistence.model.DatabaseBookmark

fun DatabaseBookmark.toBookmark(): PageBookmark {
    return PageBookmark(
        page = page.toInt(),
        remoteId = remote_id,
        lastUpdated = created_at
    )
}

fun DatabaseBookmark.toBookmarkMutation(): BookmarkMutation = BookmarkMutation(
    page = page.toInt(),
    remoteId = remote_id,
    mutationType = if (deleted == 1L) BookmarkMutationType.DELETED else BookmarkMutationType.CREATED,
    lastUpdated = created_at
)