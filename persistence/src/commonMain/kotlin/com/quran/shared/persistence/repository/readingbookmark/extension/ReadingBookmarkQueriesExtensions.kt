@file:OptIn(ExperimentalTime::class)

package com.quran.shared.persistence.repository.readingbookmark.extension

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.persistence.model.AyahReadingBookmark
import com.quran.shared.persistence.model.DatabaseReadingBookmark
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmark
import com.quran.shared.persistence.util.toPlatform
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

internal fun DatabaseReadingBookmark.toReadingBookmark(): ReadingBookmark {
    val lastUpdated = Instant.fromEpochMilliseconds(modified_at).toPlatform()
    val localId = local_id.toString()
    return when (bookmark_type) {
        "AYAH" -> AyahReadingBookmark(
            sura = requireNotNull(sura).toInt(),
            ayah = requireNotNull(ayah).toInt(),
            lastUpdated = lastUpdated,
            localId = localId
        )
        "PAGE" -> PageReadingBookmark(
            page = requireNotNull(page).toInt(),
            lastUpdated = lastUpdated,
            localId = localId
        )
        else -> error("Unsupported reading bookmark type: $bookmark_type")
    }
}

internal fun DatabaseReadingBookmark.toReadingBookmarkMutation(): LocalModelMutation<ReadingBookmark> {
    val mutation = when {
        deleted == 1L -> Mutation.DELETED
        is_edited == 1L -> Mutation.MODIFIED
        else -> Mutation.CREATED
    }
    return LocalModelMutation(
        mutation = mutation,
        model = toReadingBookmark(),
        remoteID = remote_id,
        localID = local_id.toString()
    )
}
