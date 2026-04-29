@file:OptIn(ExperimentalTime::class)

package com.quran.shared.persistence.repository.readingsession.extension

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.persistence.model.DatabaseReadingSession
import com.quran.shared.persistence.model.ReadingSession
import com.quran.shared.persistence.util.toPlatform
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

internal fun DatabaseReadingSession.toReadingSession(): ReadingSession {
    return ReadingSession(
        chapterNumber = chapter_number.toInt(),
        verseNumber = verse_number.toInt(),
        lastUpdated = Instant.fromEpochMilliseconds(modified_at).toPlatform(),
        localId = local_id.toString()
    )
}

internal fun DatabaseReadingSession.toReadingSessionMutation(): LocalModelMutation<ReadingSession> {
    val mutation = when {
        deleted == 1L -> Mutation.DELETED
        is_edited == 1L -> Mutation.MODIFIED
        else -> Mutation.CREATED
    }
    return LocalModelMutation(
        mutation = mutation,
        model = toReadingSession(),
        remoteID = remote_id,
        localID = local_id.toString()
    )
}
