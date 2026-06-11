package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import kotlinx.serialization.json.JsonObject

internal fun <Model> LocalModelMutation<Model>.toSyncMutation(
    resourceName: String,
    resourceData: (Model) -> JsonObject,
    timestamp: (Model) -> Long,
    includeDataForDeletes: Boolean = false
): SyncMutation =
    SyncMutation(
        resource = resourceName,
        resourceId = remoteID,
        mutation = mutation,
        data = if (mutation == Mutation.DELETED && !includeDataForDeletes) null else resourceData(model),
        timestamp = timestamp(model)
    )
