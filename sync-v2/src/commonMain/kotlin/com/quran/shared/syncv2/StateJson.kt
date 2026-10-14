package com.quran.shared.syncv2

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

/**
 * A typed field of a [State]. A type declares its fields once (`val SURA = Field.int("sura")`), builds states with
 * [stateOf] (`stateOf(SURA(2), AYAH(255))`) and reads them with `state[SURA]`.
 */
class Field<T : Any> private constructor(
    val name: String,
    private val decode: (JsonPrimitive) -> T?,
    private val encode: (T) -> JsonPrimitive
) {
    operator fun invoke(value: T): Pair<String, JsonElement> = name to encode(value)

    fun read(state: State): T? = (state[name] as? JsonPrimitive)?.let(decode)

    companion object {
        fun int(name: String) = Field<Int>(name, { it.intOrNull }, { JsonPrimitive(it) })
        fun long(name: String) = Field<Long>(name, { it.longOrNull }, { JsonPrimitive(it) })
        fun bool(name: String) = Field<Boolean>(name, { it.booleanOrNull }, { JsonPrimitive(it) })
        fun string(name: String) = Field<String>(name, { it.takeIf { p -> p.isString }?.contentOrNull }, { JsonPrimitive(it) })
    }
}

fun stateOf(vararg fields: Pair<String, JsonElement>): State = JsonObject(mapOf(*fields))

/** A copy of the state with [fields] replaced. */
fun State.with(vararg fields: Pair<String, JsonElement>): State = JsonObject(this + fields)

/** Reads a field the type always writes: a missing one is a bug in the type's own mapping. */
operator fun <T : Any> State.get(field: Field<T>): T = field.read(this) ?: error("${field.name} missing in $this")

fun <T : Any> State.getOrNull(field: Field<T>): T? = field.read(this)

// Raw server JSON (GET entries and POST responses), where any field may be absent.
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/** The server sends client timestamps as ISO strings; it accepts ISO strings or epoch milliseconds. */
internal fun JsonObject.epochMillis(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?.toEpochMilliseconds()
}

internal fun isoTime(epochMillis: Long): String = Instant.fromEpochMilliseconds(epochMillis).toString()
