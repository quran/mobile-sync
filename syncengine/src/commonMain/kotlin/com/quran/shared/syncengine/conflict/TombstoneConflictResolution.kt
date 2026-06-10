package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.Mutation

internal fun <Model> ResourceConflict<Model>.resolveLocalDeleteOverRemoteCreateEcho(): ConflictResolutionResult<Model>? {
    val remoteCreateIds = remoteMutations
        .filter { it.mutation == Mutation.CREATED }
        .map { it.remoteID }
        .toSet()
    val localDeletesToPush = localMutations.filter { local ->
        local.mutation == Mutation.DELETED &&
            local.remoteID != null &&
            remoteCreateIds.contains(local.remoteID)
    }
    if (localDeletesToPush.isEmpty()) {
        return null
    }
    val localDeleteRemoteIds = localDeletesToPush
        .mapNotNull { it.remoteID }
        .toSet()
    val remoteMutationsToPersist = remoteMutations.filterNot { remote ->
        remote.mutation == Mutation.CREATED &&
            localDeleteRemoteIds.contains(remote.remoteID)
    }
    val persistedRemoteIds = remoteMutationsToPersist
        .map { it.remoteID }
        .toSet()
    // The adapter clears every preprocessed local mutation after success, so locals not covered
    // by a retained remote mutation must stay pushable instead of being silently cleared.
    val localMutationsToPush = localMutations.filter { local ->
        val isResolvedByRemotePersistence = local.remoteID != null &&
            persistedRemoteIds.contains(local.remoteID)

        !isResolvedByRemotePersistence &&
            (local.remoteID != null || local.mutation != Mutation.DELETED)
    }

    return ConflictResolutionResult(
        mutationsToPersist = remoteMutationsToPersist,
        mutationsToPush = localMutationsToPush
    )
}
