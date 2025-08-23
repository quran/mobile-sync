package com.quran.shared.persistence.repository

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.persistence.model.PageBookmark
import com.quran.shared.persistence.model.DatabasePageBookmark
import kotlinx.datetime.Instant

fun DatabasePageBookmark.toBookmark(): PageBookmark {
    return PageBookmark(
        page = page.toInt(),
        lastUpdated = Instant.fromEpochSeconds(created_at),
        localId = local_id.toString()
    )
}

fun DatabasePageBookmark.toBookmarkMutation(): LocalModelMutation<PageBookmark> = LocalModelMutation(
    mutation = if (deleted == 0L) Mutation.CREATED else Mutation.DELETED,
    model = toBookmark(),
    remoteID = remote_id,
    localID = local_id.toString()
)