package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.LocalMutationAck
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation

internal fun LocalMutationAck.markerKey(): String =
    "$localID|$resource|$facet|$observedPendingOp|$observedPendingVersion"

internal fun <Model> LocalModelMutation<Model>.withIncrementedAckIfMarked(
    markedAckKeys: Set<String>
): LocalModelMutation<Model> {
    val ack = ack ?: return this
    if (ack.markerKey() !in markedAckKeys) {
        return this
    }
    return LocalModelMutation(
        model = model,
        remoteID = remoteID,
        localID = localID,
        mutation = mutation,
        ack = ack.copy(observedPendingVersion = ack.observedPendingVersion + 1)
    )
}

internal fun <Model> List<LocalModelMutation<Model>>.filterPushableCreates(
    markedAckKeys: Set<String>,
    createMarkerKey: (LocalModelMutation<Model>) -> String?
): List<LocalModelMutation<Model>> =
    filter { mutation ->
        val createKey = createMarkerKey(mutation)
        createKey == null || createKey in markedAckKeys
    }

internal fun <Model> List<LocalModelMutation<Model>>.filterClearableCreates(
    markedAckKeys: Set<String>,
    pushedCreateAckKeys: Set<String>,
    createMarkerKey: (LocalModelMutation<Model>) -> String?
): List<LocalModelMutation<Model>> =
    filter { mutation ->
        val createKey = createMarkerKey(mutation)
        createKey == null || createKey !in pushedCreateAckKeys || createKey in markedAckKeys
    }

internal fun <Model, ConflictKey> List<LocalModelMutation<Model>>.mapReplayCreatedClears(
    remoteMutationsToPersist: List<RemoteModelMutation<Model>>,
    conflictKey: (Model) -> ConflictKey
): List<LocalModelMutation<Model>> =
    map { local ->
        val replayedRemote = remoteMutationsToPersist.singleOrNull { remote ->
            local.mutation == Mutation.CREATED &&
                remote.mutation == Mutation.CREATED &&
                conflictKey(local.model) == conflictKey(remote.model)
        } ?: return@map local
        LocalModelMutation(
            model = replayedRemote.model,
            remoteID = replayedRemote.remoteID,
            localID = local.localID,
            mutation = replayedRemote.mutation,
            ack = local.ack
        )
    }
