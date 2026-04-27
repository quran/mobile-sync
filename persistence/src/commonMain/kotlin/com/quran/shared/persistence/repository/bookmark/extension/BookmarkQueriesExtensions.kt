@file:OptIn(ExperimentalTime::class)

package com.quran.shared.persistence.repository.bookmark.extension

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.model.DatabaseAyahBookmark
import com.quran.shared.persistence.model.DatabasePageBookmark
import com.quran.shared.persistence.util.toPlatform
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

internal fun DatabasePageBookmark.toBookmark(): Bookmark.PageBookmark {
    return Bookmark.PageBookmark(
        page = page.toInt(),
        isReading = is_reading == 1L,
        lastUpdated = Instant.fromEpochMilliseconds(modified_at).toPlatform(),
        localId = local_id.toString()
    )
}

internal fun DatabasePageBookmark.toBookmarkMutation(): LocalModelMutation<Bookmark> {
    val mutation = when {
        deleted == 1L -> Mutation.DELETED
        is_edited == 1L -> Mutation.MODIFIED
        else -> Mutation.CREATED
    }
    return LocalModelMutation(
        mutation = mutation,
        model = toBookmark(),
        remoteID = remote_id,
        localID = local_id.toString()
    )
}

internal fun DatabaseAyahBookmark.toBookmark(): Bookmark.AyahBookmark {
    return Bookmark.AyahBookmark(
        sura = sura.toInt(),
        ayah = ayah.toInt(),
        isReading = is_reading == 1L,
        lastUpdated = Instant.fromEpochMilliseconds(modified_at).toPlatform(),
        localId = local_id.toString()
    )
}

internal fun DatabaseAyahBookmark.toBookmarkMutation(): LocalModelMutation<Bookmark> {
    val mutation = when {
        deleted == 1L -> Mutation.DELETED
        is_edited == 1L -> Mutation.MODIFIED
        else -> Mutation.CREATED
    }
    return LocalModelMutation(
        mutation = mutation,
        model = toBookmark(),
        remoteID = remote_id,
        localID = local_id.toString()
    )
}