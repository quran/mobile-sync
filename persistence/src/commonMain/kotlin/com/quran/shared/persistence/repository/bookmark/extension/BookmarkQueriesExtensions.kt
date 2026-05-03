@file:OptIn(ExperimentalTime::class)

package com.quran.shared.persistence.repository.bookmark.extension

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.persistence.model.AyahBookmark
import com.quran.shared.persistence.model.DatabaseAyahBookmark
import com.quran.shared.persistence.util.toPlatform
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

internal fun DatabaseAyahBookmark.toAyahBookmark(): AyahBookmark {
    return AyahBookmark(
        sura = sura.toInt(),
        ayah = ayah.toInt(),
        lastUpdated = Instant.fromEpochMilliseconds(modified_at).toPlatform(),
        localId = local_id.toString()
    )
}

internal fun DatabaseAyahBookmark.toBookmarkMutation(): LocalModelMutation<AyahBookmark> {
    val mutation = when {
        deleted == 1L -> Mutation.DELETED
        is_edited == 1L -> Mutation.MODIFIED
        else -> Mutation.CREATED
    }
    return LocalModelMutation(
        mutation = mutation,
        model = toAyahBookmark(),
        remoteID = remote_id,
        localID = local_id.toString()
    )
}
