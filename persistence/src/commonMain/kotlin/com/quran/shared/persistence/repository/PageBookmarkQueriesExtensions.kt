@file:OptIn(kotlin.time.ExperimentalTime::class)
package com.quran.shared.persistence.repository

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.persistence.model.DatabasePageBookmark
import com.quran.shared.persistence.model.PageBookmark
import kotlin.time.Instant

internal fun DatabasePageBookmark.toBookmark(): PageBookmark {
    return PageBookmark(
        page = page.toInt(),
        lastUpdated = Instant.fromEpochSeconds(created_at),
        localId = local_id.toString()
    )
}

internal fun DatabasePageBookmark.toBookmarkMutation(): LocalModelMutation<PageBookmark> = LocalModelMutation(
    mutation = if (deleted == 0L) Mutation.CREATED else Mutation.DELETED,
    model = toBookmark(),
    remoteID = remote_id,
    localID = local_id.toString()
)