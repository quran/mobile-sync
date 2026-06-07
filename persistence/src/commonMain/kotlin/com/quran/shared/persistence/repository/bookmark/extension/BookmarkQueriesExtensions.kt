@file:OptIn(ExperimentalTime::class)

package com.quran.shared.persistence.repository.bookmark.extension

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.persistence.model.AyahBookmark
import com.quran.shared.persistence.model.DatabaseBookmark
import com.quran.shared.persistence.util.toPlatform
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

internal fun DatabaseBookmark.toAyahBookmark(): AyahBookmark {
    return AyahBookmark(
        sura = requireNotNull(sura).toInt(),
        ayah = requireNotNull(ayah).toInt(),
        lastUpdated = Instant.fromEpochMilliseconds(modified_at).toPlatform(),
        localId = local_id.toString()
    )
}

internal fun DatabaseBookmark.toBookmarkMutation(): LocalModelMutation<AyahBookmark> {
    val mutation = when {
        deleted == 1L || bookmark_pending_op == "DELETED" -> Mutation.DELETED
        bookmark_pending_op == "MODIFIED" -> Mutation.MODIFIED
        else -> Mutation.CREATED
    }
    return LocalModelMutation(
        mutation = mutation,
        model = toAyahBookmark(),
        remoteID = remote_id,
        localID = local_id.toString()
    )
}
