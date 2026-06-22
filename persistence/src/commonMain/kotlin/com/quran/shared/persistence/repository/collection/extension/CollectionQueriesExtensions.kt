@file:OptIn(ExperimentalTime::class)

package com.quran.shared.persistence.repository.collection.extension

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.LOCAL_MUTATION_ENTITY_FACET
import com.quran.shared.mutations.LocalMutationAck
import com.quran.shared.mutations.LocalMutationResource
import com.quran.shared.mutations.Mutation
import com.quran.shared.persistence.input.LocalSyncCollection
import com.quran.shared.persistence.model.DatabaseCollection
import com.quran.shared.persistence.model.Collection as PersistenceCollection
import com.quran.shared.persistence.util.toPlatform
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

internal fun DatabaseCollection.toCollection(): PersistenceCollection {
    return PersistenceCollection(
        name = name,
        lastUpdated = Instant.fromEpochMilliseconds(modified_at).toPlatform(),
        localId = local_id.toString()
    )
}

internal fun DatabaseCollection.toCollectionMutation(): LocalModelMutation<LocalSyncCollection> {
    val mutation = when {
        deleted == 1L -> Mutation.DELETED
        remote_id == null -> Mutation.CREATED
        else -> Mutation.MODIFIED
    }
    val collection = LocalSyncCollection(
        name = name,
        lastUpdated = Instant.fromEpochMilliseconds(modified_at).toPlatform(),
        localId = local_id.toString(),
        createdAt = Instant.fromEpochMilliseconds(created_at).toPlatform()
    )

    return LocalModelMutation(
        mutation = mutation,
        model = collection,
        remoteID = remote_id,
        localID = local_id.toString(),
        ack = LocalMutationAck(
            localID = local_id.toString(),
            resource = LocalMutationResource.COLLECTION,
            facet = LOCAL_MUTATION_ENTITY_FACET,
            observedPendingOp = mutation,
            observedPendingVersion = pending_version
        )
    )
}
