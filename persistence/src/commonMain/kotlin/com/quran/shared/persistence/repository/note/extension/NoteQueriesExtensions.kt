@file:OptIn(ExperimentalTime::class)

package com.quran.shared.persistence.repository.note.extension

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.persistence.model.DatabaseNote
import com.quran.shared.persistence.model.Note
import com.quran.shared.persistence.util.toPlatform
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

internal fun DatabaseNote.toNote(): Note {
    val normalizedModifiedAt = normalizeEpochMillis(modified_at)
    return Note(
        body = note,
        startAyahId = start_ayah_id,
        endAyahId = end_ayah_id,
        lastUpdated = Instant.fromEpochMilliseconds(normalizedModifiedAt).toPlatform(),
        localId = local_id.toString()
    )
}

internal fun DatabaseNote.toNoteMutation(): LocalModelMutation<Note> {
    val mutation = when {
        deleted == 1L -> Mutation.DELETED
        remote_id == null -> Mutation.CREATED
        else -> Mutation.MODIFIED
    }

    return LocalModelMutation(
        mutation = mutation,
        model = toNote(),
        remoteID = remote_id,
        localID = local_id.toString()
    )
}

private fun normalizeEpochMillis(value: Long): Long {
    return if (value < 1_000_000_000_000L) value * 1000 else value
}
