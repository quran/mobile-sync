@file:OptIn(ExperimentalTime::class)

package com.quran.shared.persistence.repository.note.extension

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.LOCAL_MUTATION_ENTITY_FACET
import com.quran.shared.mutations.LocalMutationAck
import com.quran.shared.mutations.LocalMutationResource
import com.quran.shared.mutations.Mutation
import com.quran.shared.persistence.input.LocalSyncNote
import com.quran.shared.persistence.model.DatabaseNote
import com.quran.shared.persistence.model.Note
import com.quran.shared.persistence.util.QuranData
import com.quran.shared.persistence.util.toPlatform
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

internal fun DatabaseNote.toNote(): Note {
    val normalizedModifiedAt = normalizeEpochMillis(modified_at)
    val start = requireNotNull(QuranData.getSuraAyahOrNull(start_ayah_id)) {
        "Invalid start ayah id for note local_id=$local_id: $start_ayah_id"
    }
    val end = requireNotNull(QuranData.getSuraAyahOrNull(end_ayah_id)) {
        "Invalid end ayah id for note local_id=$local_id: $end_ayah_id"
    }
    return Note(
        body = note,
        startSura = start.first,
        startAyah = start.second,
        endSura = end.first,
        endAyah = end.second,
        lastUpdated = Instant.fromEpochMilliseconds(normalizedModifiedAt).toPlatform(),
        localId = local_id.toString()
    )
}

internal fun DatabaseNote.toNoteMutation(): LocalModelMutation<LocalSyncNote> {
    val mutation = when {
        deleted == 1L -> Mutation.DELETED
        remote_id == null -> Mutation.CREATED
        else -> Mutation.MODIFIED
    }
    val normalizedModifiedAt = normalizeEpochMillis(modified_at)
    val normalizedCreatedAt = normalizeEpochMillis(created_at)
    val start = requireNotNull(QuranData.getSuraAyahOrNull(start_ayah_id)) {
        "Invalid start ayah id for note local_id=$local_id: $start_ayah_id"
    }
    val end = requireNotNull(QuranData.getSuraAyahOrNull(end_ayah_id)) {
        "Invalid end ayah id for note local_id=$local_id: $end_ayah_id"
    }
    val note = LocalSyncNote(
        body = note,
        startSura = start.first,
        startAyah = start.second,
        endSura = end.first,
        endAyah = end.second,
        lastUpdated = Instant.fromEpochMilliseconds(normalizedModifiedAt).toPlatform(),
        localId = local_id.toString(),
        createdAt = Instant.fromEpochMilliseconds(normalizedCreatedAt).toPlatform()
    )

    return LocalModelMutation(
        mutation = mutation,
        model = note,
        remoteID = remote_id,
        localID = local_id.toString(),
        ack = LocalMutationAck(
            localID = local_id.toString(),
            resource = LocalMutationResource.NOTE,
            facet = LOCAL_MUTATION_ENTITY_FACET,
            observedPendingOp = mutation,
            observedPendingVersion = pending_version
        )
    )
}

private fun normalizeEpochMillis(value: Long): Long {
    return if (value < 1_000_000_000_000L) value * 1000 else value
}
