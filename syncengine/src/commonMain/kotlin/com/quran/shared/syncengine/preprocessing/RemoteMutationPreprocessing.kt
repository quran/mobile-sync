package com.quran.shared.syncengine.preprocessing

import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation

internal suspend fun <Model> List<RemoteModelMutation<Model>>.filterDeletesByLocalExistence(
    checkLocalExistence: suspend (List<String>) -> Map<String, Boolean>,
    keepDelete: (RemoteModelMutation<Model>) -> Boolean = { false }
): List<RemoteModelMutation<Model>> {
    val remoteIDsToCheck = filter { it.mutation == Mutation.DELETED }
        .map { it.remoteID }
        .distinct()
    val existenceMap = if (remoteIDsToCheck.isNotEmpty()) {
        checkLocalExistence(remoteIDsToCheck)
    } else {
        emptyMap()
    }

    return filter { mutation ->
        mutation.mutation != Mutation.DELETED ||
            existenceMap[mutation.remoteID] == true ||
            keepDelete(mutation)
    }
}

internal fun <T> RemoteModelMutation<T>.mapModifiedToCreated(): RemoteModelMutation<T> =
    when (mutation) {
        Mutation.MODIFIED ->
            RemoteModelMutation(
                model = model,
                remoteID = remoteID,
                mutation = Mutation.CREATED,
                ack = ack
            )
        Mutation.DELETED, Mutation.CREATED -> this
    }
