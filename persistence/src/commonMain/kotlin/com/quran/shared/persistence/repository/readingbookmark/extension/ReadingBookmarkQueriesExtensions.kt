@file:OptIn(ExperimentalTime::class)

package com.quran.shared.persistence.repository.readingbookmark.extension

import com.quran.shared.mutations.LOCAL_MUTATION_ENTITY_FACET
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.LocalMutationAck
import com.quran.shared.mutations.LocalMutationResource
import com.quran.shared.mutations.Mutation
import com.quran.shared.persistence.input.LocalSyncReadingBookmark
import com.quran.shared.persistence.model.AyahReadingBookmark
import com.quran.shared.persistence.model.DatabaseReadingBookmark
import com.quran.shared.persistence.model.DatabaseUnsyncedReadingBookmark
import com.quran.shared.persistence.model.EmptyReadingBookmark
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmark
import com.quran.shared.persistence.model.toReadingBookmarkSlot
import com.quran.shared.persistence.util.toPlatform
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

internal fun DatabaseReadingBookmark.toReadingBookmark(): ReadingBookmark {
    val lastUpdated = Instant.fromEpochMilliseconds(modified_at).toPlatform()
    val id = local_id.toString()
    return when (bookmark_type) {
        "AYAH" -> AyahReadingBookmark(
            sura = requireNotNull(sura).toInt(),
            ayah = requireNotNull(ayah).toInt(),
            slot = slot.toInt().toReadingBookmarkSlot(),
            name = name,
            lastUpdated = lastUpdated,
            id = id
        )
        "PAGE" -> PageReadingBookmark(
            page = requireNotNull(page).toInt(),
            slot = slot.toInt().toReadingBookmarkSlot(),
            name = name,
            lastUpdated = lastUpdated,
            id = id
        )
        null -> EmptyReadingBookmark(
            slot = slot.toInt().toReadingBookmarkSlot(),
            name = name,
            lastUpdated = lastUpdated,
            id = id
        )
        else -> error("Unsupported reading bookmark type: $bookmark_type")
    }
}

internal fun DatabaseUnsyncedReadingBookmark.toReadingBookmarkMutation(): LocalModelMutation<LocalSyncReadingBookmark> {
    val mutation = if (remote_id == null) Mutation.CREATED else Mutation.MODIFIED
    return LocalModelMutation(
        mutation = mutation,
        model = LocalSyncReadingBookmark(
            slot = slot.toInt(),
            name = name,
            type = bookmark_type,
            sura = sura?.toInt(),
            ayah = ayah?.toInt(),
            page = page?.toInt(),
            lastUpdated = Instant.fromEpochMilliseconds(modified_at).toPlatform(),
            localId = local_id.toString(),
            createdAt = Instant.fromEpochMilliseconds(created_at).toPlatform()
        ),
        remoteID = remote_id,
        localID = local_id.toString(),
        ack = LocalMutationAck(
            localID = local_id.toString(),
            resource = LocalMutationResource.READING_BOOKMARK,
            facet = LOCAL_MUTATION_ENTITY_FACET,
            observedPendingOp = mutation,
            observedPendingVersion = pending_version
        )
    )
}
