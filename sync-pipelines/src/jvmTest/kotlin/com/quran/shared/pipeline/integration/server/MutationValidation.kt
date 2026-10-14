package com.quran.shared.pipeline.integration.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A POSTed mutation after `validateMutationBody`: whitelisted fields and Joi-validated data. */
data class ValidatedMutation(
    val index: Int,
    val resource: String,
    val type: String,
    val resourceId: String?,
    /** The validated, transformed data (sanitized text, defaults filled), or null for an id-only DELETE. */
    val data: JsonObject?
)

/**
 * `resources/index.ts` `validateMutationBody` plus each resource's Joi schema. It runs as route
 * middleware, before the scope check and before the `lastMutationAt` head check, so a stale POST
 * with an invalid mutation gets 422, not 409.
 */
internal object MutationValidation {
    private const val MUTATIONS_MAX_LENGTH = 100
    private val ALLOWED = mapOf(
        "BOOKMARK" to setOf("CREATE", "DELETE"),
        "COLLECTION" to setOf("CREATE", "UPDATE", "DELETE"),
        "COLLECTION_BOOKMARK" to setOf("CREATE", "DELETE"),
        "NOTE" to setOf("CREATE", "UPDATE", "DELETE"),
        "READING_SESSION" to setOf("CREATE", "UPDATE", "DELETE"),
        "READING_BOOKMARK" to setOf("CREATE", "UPDATE", "DELETE")
    )

    fun validateBody(body: JsonElement?): List<ValidatedMutation> {
        val mutations = (body as? JsonObject)?.get("mutations") as? JsonArray
            ?: throw validationError("Invalid mutations")
        if (mutations.size > MUTATIONS_MAX_LENGTH) {
            throw validationError("Mutations must not exceed $MUTATIONS_MAX_LENGTH")
        }
        return mutations.mapIndexed { i, element ->
            try {
                validate(i, element as? JsonObject ?: JsonObject(emptyMap()))
            } catch (error: HttpError) {
                throw error.also { it.mutationIndex = i }
            }
        }
    }

    private fun validate(i: Int, mutation: JsonObject): ValidatedMutation {
        val resource = mutation.text("resource") ?: throw validationError("Missing resource at mutation[$i]")
        val allowed = ALLOWED[resource] ?: throw validationError("Invalid resource: \"$resource\" at mutation[$i]")
        val resourceId = mutation.text("resourceId")
        if (resource == "COLLECTION" && resourceId == FakeAccount.DEFAULT_COLLECTION_ID) {
            throw validationError("The default collection cannot be mutated directly.")
        }
        val type = mutation.text("type") ?: throw validationError("Missing mutation type at mutation[$i]")
        if (type !in allowed) throw validationError("Invalid mutation type: \"$type\" at mutation[$i]")
        val hasResourceId = resource != "COLLECTION_BOOKMARK"
        if ((type == "UPDATE" || type == "DELETE") && hasResourceId) {
            val raw = mutation["resourceId"]
            if (raw == null || raw is JsonNull || resourceId == "") throw validationError("Missing resourceId at mutation[$i]")
            if (raw !is JsonPrimitive || !raw.isString) throw validationError("Invalid resourceId at mutation[$i]")
        }
        val needsData = type == "CREATE" || type == "UPDATE" || !hasResourceId
        if (!needsData) return ValidatedMutation(i, resource, type, resourceId, data = null)
        val data = mutation["data"] as? JsonObject ?: throw validationError("Missing data at mutation[$i]")
        val validated = ResourceSchemas.validate(resource, type, data)
        return ValidatedMutation(i, resource, type, resourceId.takeIf { hasResourceId && type != "CREATE" }, validated)
    }
}

/** The Joi schemas the sync resources use, reduced to what the backend enforces. */
internal object ResourceSchemas {
    private val MUSHAF_IDS = setOf(1, 2, 3, 4, 5, 6, 7, 11, 19)
    private val TIMESTAMPS = setOf("clientCreatedAt", "clientUpdatedAt")
    private val RANGE = Regex("^(\\d+):(\\d+)-(\\d+):(\\d+)$")

    fun validate(resource: String, type: String, data: JsonObject): JsonObject = when (resource) {
        "BOOKMARK" -> bookmarkLocation(data, extra = setOf("isReading"), mushafKeys = setOf("mushaf", "mushafId"))
        "COLLECTION" -> collection(data)
        "COLLECTION_BOOKMARK" -> membership(type, data)
        "NOTE" -> if (type == "CREATE") noteCreate(data) else noteUpdate(data)
        "READING_SESSION" -> session(data)
        "READING_BOOKMARK" -> readingBookmark(data)
        else -> throw validationError("Invalid resource: \"$resource\"")
    }

    private fun collection(data: JsonObject): JsonObject = Out(data, TIMESTAMPS + "name").apply {
        val name = stripped("name") ?: throw validationError("\"name\" is required")
        // htmlStrip() already trims with JavaScript's trim(); Joi's trim() then changes nothing.
        if (name.length > 255) throw validationError("\"name\" length must be less than or equal to 255 characters long")
        put("name", name)
        timestamps()
    }.build()

    private fun membership(type: String, data: JsonObject): JsonObject {
        if (type == "DELETE" && "bookmarkId" in data) {
            return Out(data, setOf("bookmarkId", "collectionId")).apply {
                put("bookmarkId", stripped("bookmarkId") ?: throw validationError("\"bookmarkId\" is required"))
                put("collectionId", stripped("collectionId") ?: throw validationError("\"collectionId\" is required"))
            }.build()
        }
        val location = bookmarkLocation(data, extra = setOf("collectionId"), mushafKeys = setOf("mushaf", "mushafId"))
        val collectionId = location.text("collectionId")?.let(::htmlStrip)
            ?: throw validationError("\"collectionId\" is required")
        return JsonObject(location + ("collectionId" to JsonPrimitive(collectionId)))
    }

    /** `ayahBookmarkBodyValidator` or `surahJuzOrPageBookmarkBodyValidator`, chosen by `type`. */
    private fun bookmarkLocation(data: JsonObject, extra: Set<String>, mushafKeys: Set<String>): JsonObject {
        val type = data.text("type")?.let(::htmlStrip) ?: "ayah"
        val isAyah = type == "ayah"
        val keys = TIMESTAMPS + mushafKeys + extra + setOf("key", "type") + if (isAyah) setOf("verseNumber") else emptySet()
        return Out(data, keys).apply {
            if (type !in setOf("ayah", "juz", "page", "surah")) throw validationError("\"type\" must be one of [ayah, juz, page, surah]")
            val key = int("key") ?: throw validationError("\"key\" is required")
            put("key", key)
            put("type", type)
            if (isAyah) {
                val verse = int("verseNumber") ?: throw validationError("\"verseNumber\" is required")
                put("verseNumber", verse)
                requireAyah(key, verse)
            }
            mushaf(mushafKeys, required = true)
            if ("isReading" in extra && "isReading" in data) {
                put("isReading", data["isReading"].takeUnless { it is JsonNull }?.let { boolean("isReading") })
            }
            extra.filter { it != "isReading" && it in data }.forEach { copy(it) }
            timestamps()
        }.build()
    }

    private fun noteCreate(data: JsonObject): JsonObject =
        Out(data, TIMESTAMPS + setOf("body", "saveToQR", "attachedEntity", "ranges")).apply {
            put("body", noteBody())
            put("saveToQR", if ("saveToQR" in data) boolean("saveToQR") else false)
            (data["attachedEntity"] as? JsonObject)?.let { entity ->
                Out(entity, setOf("entityId", "entityType", "entityMetadata")).apply {
                    stripped("entityId") ?: throw validationError("\"attachedEntity.entityId\" is required")
                    if (stripped("entityType") != "reflection") throw validationError("\"attachedEntity.entityType\" must be [reflection]")
                }
                copy("attachedEntity")
            }
            val ranges = data["ranges"] as? JsonArray ?: throw validationError("\"ranges\" is required")
            if (ranges.isEmpty()) throw validationError("\"ranges\" must contain at least 1 items")
            ranges.forEachIndexed { i, range ->
                val text = (range as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (text == null || !RANGE.matches(text)) {
                    throw validationError("\"ranges[$i]\" with value \"${range}\" fails to match the required pattern: $RANGE")
                }
            }
            copy("ranges")
            timestamps()
        }.build()

    private fun noteUpdate(data: JsonObject): JsonObject = Out(data, TIMESTAMPS + setOf("body", "saveToQR")).apply {
        put("body", noteBody())
        put("saveToQR", if ("saveToQR" in data) boolean("saveToQR") else false)
        timestamps()
    }.build()

    private fun Out.noteBody(): String {
        val body = stripped("body") ?: throw validationError("\"body\" is required")
        // Joi checks min and max on the value htmlStrip returned: sanitized, escaped and trimmed.
        if (body.length < 6) throw validationError("\"body\" length must be at least 6 characters long")
        if (body.length > 10_000) throw validationError("\"body\" length must be less than or equal to 10000 characters long")
        return body
    }

    private fun session(data: JsonObject): JsonObject = Out(data, TIMESTAMPS + setOf("chapterNumber", "verseNumber")).apply {
        put("chapterNumber", int("chapterNumber", min = 1) ?: throw validationError("\"chapterNumber\" is required"))
        put("verseNumber", int("verseNumber", min = 1) ?: throw validationError("\"verseNumber\" is required"))
        timestamps()
    }.build()

    /** `readingBookmarkSyncWriteValidator`: a placed slot, or the unplaced form with every location field null. */
    private fun readingBookmark(data: JsonObject): JsonObject {
        val locationKeys = setOf("mushafId", "type", "group", "key", "verseNumber")
        val unplaced = locationKeys.any { data[it] is JsonNull }
        val type = data.text("type")?.let(::htmlStrip) ?: "ayah"
        val keys = TIMESTAMPS + setOf("slot", "name", "metadata") + when {
            unplaced -> locationKeys
            type == "ayah" -> setOf("mushafId", "type", "key", "verseNumber")
            else -> setOf("mushafId", "type", "key")
        }
        return Out(data, keys).apply {
            if (unplaced) {
                locationKeys.forEach { key ->
                    if (data[key] !is JsonNull) throw validationError("\"$key\" must be [null]")
                    put(key, null as String?)
                }
            } else {
                if (type !in setOf("ayah", "juz", "page", "surah")) throw validationError("\"type\" must be one of [ayah, juz, page, surah]")
                val key = int("key", min = if (type == "ayah") null else 1) ?: throw validationError("\"key\" is required")
                put("key", key)
                put("type", type)
                if (type == "ayah") {
                    val verse = int("verseNumber", min = 1) ?: throw validationError("\"verseNumber\" is required")
                    put("verseNumber", verse)
                    requireAyah(key, verse)
                }
                mushaf(setOf("mushafId"), required = true)
            }
            val slot = int("slot") ?: throw validationError("\"slot\" is required")
            if (slot !in 1..3) throw validationError("\"slot\" must be less than or equal to 3")
            put("slot", slot)
            if ("name" in data) put("name", readingBookmarkName(data["name"]))
            val metadata = data["metadata"]
            if (metadata is JsonObject && metadata.size > 10) throw validationError("\"metadata\" must have less than or equal to 10 keys")
            if (metadata != null && metadata !is JsonObject && metadata !is JsonNull) throw validationError("\"metadata\" must be of type object")
            put("metadata", metadata as? JsonObject)
            timestamps()
        }.build()
    }

    /** `normalizeReadingBookmarkName`: trimmed, and empty becomes null. */
    private fun readingBookmarkName(value: JsonElement?): String? {
        if (value == null || value is JsonNull) return null
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw validationError("\"name\" must be a string")
        val normalized = text.jsTrim().ifEmpty { return null }
        if (normalized.length > 255) throw validationError("\"name\" length must be less than or equal to 255 characters long")
        return normalized
    }

    private fun Out.mushaf(keys: Set<String>, required: Boolean) {
        val present = keys.filter { it in data && data[it] !is JsonNull }
        if (required && present.isEmpty()) {
            throw validationError("\"value\" must contain at least one of [${keys.joinToString()}]")
        }
        val values = present.map { key ->
            int(key)?.takeIf { it in MUSHAF_IDS } ?: throw validationError("\"$key\" must be one of ${MUSHAF_IDS.sorted()}")
        }
        if (values.distinct().size > 1) throw validationError("`mushafId` and `mushaf` must match when both are provided")
        present.forEachIndexed { i, key -> put(key, values[i]) }
    }

    private fun requireAyah(sura: Int, ayah: Int) {
        if (sura !in 1..114) throw validationError("$sura is not a valid Surah number!")
        if (!Verses.exists(sura, ayah)) throw validationError("$ayah is not a valid Ayah number of Surah $sura!")
    }
}

/** Builds validated output from [data], rejecting keys outside [allowed] like Joi's default `unknown(false)`. */
internal class Out(val data: JsonObject, allowed: Set<String>) {
    private val out = LinkedHashMap<String, JsonElement>()

    init {
        data.keys.firstOrNull { it !in allowed }?.let { throw validationError("\"$it\" is not allowed") }
    }

    fun put(key: String, value: String?) { out[key] = JsonPrimitive(value) }
    fun put(key: String, value: Int) { out[key] = JsonPrimitive(value) }
    fun put(key: String, value: Boolean?) { out[key] = JsonPrimitive(value) }
    fun put(key: String, value: JsonObject?) { out[key] = value ?: JsonNull }
    fun copy(key: String) { data[key]?.let { out[key] = it } }
    fun build() = JsonObject(out)

    /** A string field after `htmlStrip()`; throws when nothing is left. Null when absent. */
    fun stripped(key: String): String? {
        val value = data[key] ?: return null
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw validationError("\"$key\" must be a string")
        return htmlStrip(text) ?: throw validationError("\"$key\" must not contain html tags")
    }

    /** A Joi number with `convert`: numbers or numeric strings; integers only. */
    fun int(key: String, min: Int? = null): Int? {
        val value = data[key] as? JsonPrimitive ?: return null
        if (value is JsonNull) return null
        val number = value.longOrNull ?: value.contentOrNull?.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toLong()
            ?: throw validationError("\"$key\" must be ${if (value.contentOrNull?.toDoubleOrNull() != null) "an integer" else "a number"}")
        if (min != null && number < min) throw validationError("\"$key\" must be greater than or equal to $min")
        return number.toInt()
    }

    fun boolean(key: String): Boolean {
        val value = data[key] as? JsonPrimitive
        return value?.booleanOrNull ?: throw validationError("\"$key\" must be a boolean")
    }

    /** Client timestamps: an ISO 8601 string or JavaScript milliseconds, stored and echoed as a Date. */
    fun timestamps() {
        listOf("clientCreatedAt", "clientUpdatedAt").forEach { key ->
            val value = data[key] ?: return@forEach
            val millis = parseClientTimestamp(value) ?: throw validationError("\"$key\" must be in ISO 8601 date format")
            out[key] = JsonPrimitive(isoMillis(millis))
        }
    }
}

internal fun parseClientTimestamp(value: JsonElement): Long? {
    val primitive = value as? JsonPrimitive ?: return null
    if (!primitive.isString) return primitive.longOrNull
    return runCatching { java.time.Instant.parse(primitive.content).toEpochMilli() }.getOrNull()
}

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

internal fun JsonObject.millis(key: String): Long? = this[key]?.let(::parseClientTimestamp)
