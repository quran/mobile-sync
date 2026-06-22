package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

internal fun <Model> LocalModelMutation<Model>.toSyncMutation(
    resourceName: String,
    resourceData: (Model) -> JsonObject,
    timestamp: (Model) -> Long,
    createdTimestamp: (Model) -> Long? = { null },
    includeDataForDeletes: Boolean = false
): SyncMutation =
    SyncMutation(
        resource = resourceName,
        resourceId = remoteID,
        mutation = mutation,
        data = when {
            mutation == Mutation.DELETED && !includeDataForDeletes -> null
            else -> resourceData(model).withClientTimestamps(
                mutation = mutation,
                clientCreatedAt = createdTimestamp(model),
                clientUpdatedAt = timestamp(model)
            )
        },
        timestamp = timestamp(model)
    )

private fun JsonObject.withClientTimestamps(
    mutation: Mutation,
    clientCreatedAt: Long?,
    clientUpdatedAt: Long
): JsonObject {
    if (mutation == Mutation.DELETED) {
        return this
    }
    return buildJsonObject {
        this@withClientTimestamps.forEach { (key, value) ->
            put(key, value)
        }
        clientCreatedAt?.let { put("clientCreatedAt", it.toIsoTimestamp()) }
        put("clientUpdatedAt", clientUpdatedAt.toIsoTimestamp())
    }
}

private fun Long.toIsoTimestamp(): String =
    Instant.fromEpochMilliseconds(this).toString()
