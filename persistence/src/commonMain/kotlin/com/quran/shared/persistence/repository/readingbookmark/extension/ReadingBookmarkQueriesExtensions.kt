@file:OptIn(ExperimentalTime::class)

package com.quran.shared.persistence.repository.readingbookmark.extension

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.persistence.model.AyahReadingBookmark
import com.quran.shared.persistence.model.DatabaseBookmark
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmark
import com.quran.shared.persistence.util.toPlatform
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

internal fun DatabaseBookmark.toReadingBookmark(): ReadingBookmark {
    val lastUpdated = Instant.fromEpochMilliseconds(reading_modified_at ?: modified_at).toPlatform()
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

internal fun DatabaseBookmark.toAyahReadingBookmark(): AyahReadingBookmark {
    val lastUpdated = Instant.fromEpochMilliseconds(reading_modified_at ?: modified_at).toPlatform()
    val localId = local_id.toString()
    return when (bookmark_type) {
        "AYAH" -> AyahReadingBookmark(
            sura = requireNotNull(sura).toInt(),
            ayah = requireNotNull(ayah).toInt(),
            lastUpdated = lastUpdated,
            localId = localId
        )
        else -> error("Unsupported reading bookmark type: $bookmark_type")
    }
}

internal fun DatabaseBookmark.toPageReadingBookmark(): PageReadingBookmark {
    val lastUpdated = Instant.fromEpochMilliseconds(reading_modified_at ?: modified_at).toPlatform()
    val localId = local_id.toString()
    return when (bookmark_type) {
        "PAGE" -> PageReadingBookmark(
            page = requireNotNull(page).toInt(),
            lastUpdated = lastUpdated,
            localId = localId
        )
        else -> error("Unsupported reading bookmark type: $bookmark_type")
    }
}

internal fun DatabaseBookmark.toReadingBookmarkMutation(): LocalModelMutation<ReadingBookmark> {
    val mutation = when {
        deleted == 1L || reading_pending_op == "DELETED" -> Mutation.DELETED
        reading_pending_op == "MODIFIED" -> Mutation.MODIFIED
        else -> Mutation.CREATED
    }
    return LocalModelMutation(
        mutation = mutation,
        model = toReadingBookmark(),
        remoteID = remote_id,
        localID = local_id.toString()
    )
}
