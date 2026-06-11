package com.quran.shared.syncengine

import com.quran.shared.mutations.RemoteModelMutation

internal fun <Model> List<SyncMutation>.mapSimpleRemoteModelMutations(
    resourceName: String,
    toModel: (SyncMutation) -> Model?
): List<RemoteModelMutation<Model>> {
    return mapNotNull { mutation ->
        if (!mutation.resource.equals(resourceName, ignoreCase = true)) {
            return@mapNotNull null
        }
        val remoteId = mutation.requireSimpleResourceRemoteId(resourceName)
        RemoteModelMutation(
            model = toModel(mutation) ?: return@mapNotNull null,
            remoteID = remoteId,
            mutation = mutation.mutation
        )
    }
}
