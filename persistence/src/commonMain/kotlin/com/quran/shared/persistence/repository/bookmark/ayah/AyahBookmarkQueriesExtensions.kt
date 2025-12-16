package com.quran.shared.persistence.repository.bookmark.ayah

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.persistence.model.AyahBookmark
import com.quran.shared.persistence.model.DatabaseAyahBookmark
import com.quran.shared.persistence.util.toPlatform
import kotlin.time.Instant

internal fun DatabaseAyahBookmark.toBookmark(): AyahBookmark {
    return AyahBookmark(
        sura.toInt(),
        ayah.toInt(),
        page.toInt(),
        Instant.fromEpochSeconds(created_at).toPlatform(),
        local_id.toString()
    )
}

internal fun DatabaseAyahBookmark.toBookmarkMutation(): LocalModelMutation<AyahBookmark> =
    LocalModelMutation(
        mutation = if (deleted == 0L) Mutation.CREATED else Mutation.DELETED,
        model = toBookmark(),
        remoteID = remote_id,
        localID = local_id.toString()
    )