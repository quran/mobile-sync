package com.quran.shared.syncengine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonObject?.stringOrNull(key: String): String? =
    this?.get(key)?.jsonPrimitive?.contentOrNull

internal fun JsonObject?.intOrNull(key: String): Int? =
    this?.get(key)?.jsonPrimitive?.intOrNull

internal fun JsonObject?.booleanOrNull(key: String): Boolean? =
    this?.get(key)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

internal fun JsonObject?.stringListOrNull(key: String): List<String>? {
    val jsonArray = this?.get(key)?.jsonArray ?: return null
    return jsonArray.mapNotNull { element -> element.jsonPrimitive.contentOrNull }
}
