package com.quran.shared.persistence.repository

import com.quran.shared.persistence.model.PageBookmark
import com.quran.shared.persistence.model.PageBookmarkMutation
import com.quran.shared.persistence.model.PageBookmarkMutationType
import com.quran.shared.persistence.model.DatabasePageBookmark

fun DatabasePageBookmark.toBookmark(): PageBookmark {
    return PageBookmark(
        page = page.toInt(),
        lastUpdated = created_at,
        remoteId = remote_id
    )
}

fun DatabasePageBookmark.toBookmarkMutation(): PageBookmarkMutation = PageBookmarkMutation(
    page = page.toInt(),
    remoteId = remote_id,
    mutationType = if (deleted == 1L) PageBookmarkMutationType.DELETED else PageBookmarkMutationType.CREATED,
    lastUpdated = created_at
)