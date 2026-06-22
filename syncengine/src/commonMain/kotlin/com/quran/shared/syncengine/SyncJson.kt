package com.quran.shared.syncengine

import com.quran.shared.mutations.Mutation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

internal fun JsonObject?.stringOrNull(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject?.intOrNull(key: String): Int? =
    this?.get(key)?.jsonPrimitive?.intOrNull

internal fun JsonObject?.booleanOrNull(key: String): Boolean? =
    this?.get(key)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

internal fun JsonObject?.stringListOrNull(key: String): List<String>? {
    val jsonArray = this?.get(key)?.jsonArray ?: return null
    return jsonArray.mapNotNull { element -> element.jsonPrimitive.contentOrNull }
}

internal fun SyncMutation.serverTimestampInstant(): Instant =
    Instant.fromEpochMilliseconds(timestamp ?: 0)

internal fun SyncMutation.clientUpdatedAtInstant(): Instant {
    val fallback = serverTimestampInstant()
    return if (mutation == Mutation.DELETED) {
        fallback
    } else {
        data.stringOrNull("clientUpdatedAt")?.toInstantOrNull() ?: fallback
    }
}

internal fun SyncMutation.clientCreatedAtInstant(): Instant? {
    if (mutation == Mutation.DELETED) {
        return null
    }
    return data.stringOrNull("clientCreatedAt")?.toInstantOrNull() ?: serverTimestampInstant()
}

private fun String.toInstantOrNull(): Instant? =
    runCatching { Instant.parse(this) }.getOrNull()
