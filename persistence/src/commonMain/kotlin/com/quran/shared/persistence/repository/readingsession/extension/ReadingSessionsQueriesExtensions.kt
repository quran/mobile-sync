@file:OptIn(ExperimentalTime::class)

package com.quran.shared.persistence.repository.readingsession.extension

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.LOCAL_MUTATION_ENTITY_FACET
import com.quran.shared.mutations.LocalMutationAck
import com.quran.shared.mutations.LocalMutationResource
import com.quran.shared.mutations.Mutation
import com.quran.shared.persistence.input.LocalSyncReadingSession
import com.quran.shared.persistence.model.DatabaseReadingSession
import com.quran.shared.persistence.model.ReadingSession
import com.quran.shared.persistence.util.toPlatform
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

internal fun DatabaseReadingSession.toReadingSession(): ReadingSession {
    return ReadingSession(
        sura = chapter_number.toInt(),
        ayah = verse_number.toInt(),
        lastUpdated = Instant.fromEpochMilliseconds(modified_at).toPlatform(),
        localId = local_id.toString()
    )
}

internal fun DatabaseReadingSession.toReadingSessionMutation(): LocalModelMutation<LocalSyncReadingSession> {
    val mutation = when {
        deleted == 1L -> Mutation.DELETED
        is_edited == 1L -> Mutation.MODIFIED
        else -> Mutation.CREATED
    }
    val readingSession = LocalSyncReadingSession(
        sura = chapter_number.toInt(),
        ayah = verse_number.toInt(),
        lastUpdated = Instant.fromEpochMilliseconds(modified_at).toPlatform(),
        localId = local_id.toString(),
        createdAt = Instant.fromEpochMilliseconds(created_at).toPlatform()
    )
    return LocalModelMutation(
        mutation = mutation,
        model = readingSession,
        remoteID = remote_id,
        localID = local_id.toString(),
        ack = LocalMutationAck(
            localID = local_id.toString(),
            resource = LocalMutationResource.READING_SESSION,
            facet = LOCAL_MUTATION_ENTITY_FACET,
            observedPendingOp = mutation,
            observedPendingVersion = pending_version
        )
    )
}
