@file:OptIn(ExperimentalTime::class)

package com.quran.shared.persistence.repository.collection.extension

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
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

internal fun DatabaseCollection.toCollectionMutation(): LocalModelMutation<PersistenceCollection> {
    val mutation = when {
        deleted == 1L -> Mutation.DELETED
        remote_id == null -> Mutation.CREATED
        else -> Mutation.MODIFIED
    }

    return LocalModelMutation(
        mutation = mutation,
        model = toCollection(),
        remoteID = remote_id,
        localID = local_id.toString()
    )
}
