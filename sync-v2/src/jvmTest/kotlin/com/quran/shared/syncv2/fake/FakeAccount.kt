package com.quran.shared.syncv2.fake

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.Instant

class HttpError(
    val status: Int,
    val code: String,
    override val message: String,
    val details: Map<String, String> = emptyMap()
) : Exception(message)

/** One row of the server's ResourceLog: what GET reports for a resource. */
data class LogRow(
    val resource: String,
    val id: String,
    val createdAt: Long,
    val updatedAt: Long? = null,
    val deletedAt: Long? = null
) {
    val lastChangeAt: Long get() = maxOf(createdAt, updatedAt ?: 0, deletedAt ?: 0)
}

data class FCollection(val name: String, val source: String? = null, val createdAt: String, val updatedAt: String)
data class FBookmark(
    val key: Int,
    val verse: Int,
    val inDefault: Boolean,
    val isReading: Boolean = false,
    val createdAt: String,
    val updatedAt: String
)
data class FMembership(val collectionId: String, val bookmarkId: String, val createdAt: String, val updatedAt: String = createdAt)
data class FSession(val chapter: Int, val verse: Int, val createdAt: String, val updatedAt: String)
data class FNote(val body: String, val ranges: List<String>, val createdAt: String, val updatedAt: String, val archivedAt: String? = null)

/**
 * A reading bookmark slot; a cleared one has no location. Like the backend it stores the bookmark [group]; it also
 * keeps the written [mushafId], which GET returns only with [FakeAccount.keepsMushafId]. Client timestamps are null
 * after a DELETE clears it.
 */
data class FReadingBookmark(
    val slot: Int,
    val name: String?,
    val type: String?,
    val key: Int?,
    val verse: Int?,
    val group: String?,
    val metadata: JsonObject?,
    val createdAt: String?,
    val updatedAt: String?,
    val mushafId: Int? = null
)

/**
 * One user's data on the fake server. All entities are immutable values, so a snapshot (for the atomic POST
 * rollback) is a shallow copy of the maps.
 */
class FakeAccount(
    private var clock: Long,
    val collections: LinkedHashMap<String, FCollection> = linkedMapOf(),
    val bookmarks: LinkedHashMap<String, FBookmark> = linkedMapOf(),
    val memberships: LinkedHashMap<String, FMembership> = linkedMapOf(),
    val sessions: LinkedHashMap<String, FSession> = linkedMapOf(),
    val notes: LinkedHashMap<String, FNote> = linkedMapOf(),
    val readingBookmarks: LinkedHashMap<String, FReadingBookmark> = linkedMapOf(),
    val logs: LinkedHashMap<String, LogRow> = linkedMapOf(),
    private var ids: Int = 0
) {
    /** The virtual Favorites collection's place in the sync head (the user's createdAt). */
    val defaultCollectionAt: Long = clock

    val head: Long get() = maxOf(defaultCollectionAt, logs.values.maxOfOrNull { it.lastChangeAt } ?: 0)

    fun snapshot() = FakeAccount(
        clock, LinkedHashMap(collections), LinkedHashMap(bookmarks), LinkedHashMap(memberships),
        LinkedHashMap(sessions), LinkedHashMap(notes), LinkedHashMap(readingBookmarks), LinkedHashMap(logs), ids
    )

    fun restore(from: FakeAccount) {
        clock = from.clock
        ids = from.ids
        listOf(collections to from.collections, bookmarks to from.bookmarks, memberships to from.memberships,
            sessions to from.sessions, notes to from.notes, readingBookmarks to from.readingBookmarks, logs to from.logs).forEach { (target, source) ->
            @Suppress("UNCHECKED_CAST")
            (target as MutableMap<String, Any>).apply { clear(); putAll(source as Map<String, Any>) }
        }
    }

    fun newId(prefix: String) = "$prefix${++ids}"

    fun now(): String = Instant.fromEpochMilliseconds(clock).toString()

    /** ResourceLog semantics: CREATE resets the row, UPDATE and DELETE stamp it; time is monotonic per user. */
    fun log(resource: String, id: String, kind: String) {
        clock += 1
        val key = "$resource:$id"
        val existing = logs[key]
        logs[key] = when {
            existing == null || kind == "CREATE" -> when (kind) {
                "UPDATE" -> LogRow(resource, id, clock, updatedAt = clock)
                "DELETE" -> LogRow(resource, id, clock, deletedAt = clock)
                else -> LogRow(resource, id, clock)
            }
            kind == "UPDATE" -> existing.copy(updatedAt = clock)
            else -> existing.copy(deletedAt = clock)
        }
    }

    fun tick(): Long = ++clock

    /** The data GET returns for a live resource, or `{}` when it vanished. */
    fun dataOf(row: LogRow): JsonObject = when (row.resource) {
        "COLLECTION" -> collections[row.id]?.let {
            buildJsonObject {
                put("name", it.name)
                put("slug", it.name.lowercase().replace(' ', '-'))
                put("isSystem", isSystemName(it.name))
                it.source?.let { source -> put("metadata", buildJsonObject { put("source", source) }) }
                put("clientCreatedAt", it.createdAt)
                put("clientUpdatedAt", it.updatedAt)
            }
        }
        "BOOKMARK" -> bookmarks[row.id]?.let {
            buildJsonObject {
                put("key", it.key)
                put("verseNumber", it.verse)
                put("bookmarkType", "ayah")
                put("bookmarkGroup", "verses_6236")
                put("isInDefaultCollection", it.inDefault)
                put("isReading", it.isReading)
                put("clientCreatedAt", it.createdAt)
                put("clientUpdatedAt", it.updatedAt)
            }
        }
        "COLLECTION_BOOKMARK" -> {
            val (collectionId, bookmarkId) = row.id.split("-", limit = 2)
            val times = if (collectionId == DEFAULT_ID) {
                bookmarks[bookmarkId]?.takeIf { it.inDefault }?.let { it.createdAt to it.updatedAt }
            } else {
                memberships[row.id]?.let { it.createdAt to it.updatedAt }
            }
            buildJsonObject {
                put("collectionId", collectionId)
                put("bookmarkId", bookmarkId)
                times?.let { (createdAt, updatedAt) ->
                    put("clientCreatedAt", createdAt)
                    put("clientUpdatedAt", updatedAt)
                }
            }
        }
        "READING_SESSION" -> sessions[row.id]?.let {
            buildJsonObject {
                put("chapterNumber", it.chapter)
                put("verseNumber", it.verse)
                put("clientCreatedAt", it.createdAt)
                put("clientUpdatedAt", it.updatedAt)
            }
        }
        "NOTE" -> notes[row.id]?.let {
            buildJsonObject {
                put("body", it.body)
                put("ranges", JsonArray(it.ranges.map(::JsonPrimitive)))
                put("clientCreatedAt", it.createdAt)
                put("clientUpdatedAt", it.updatedAt)
                put("archivedAt", it.archivedAt?.let(::JsonPrimitive) ?: JsonNull)
            }
        }
        "READING_BOOKMARK" -> readingBookmarks[row.id]?.let(::readingBookmarkData)
        else -> null
    } ?: JsonObject(emptyMap())

    /** The stand-in GET renders for `pages_604`: 5 today, 1 on the upcoming backend. */
    @Volatile var pages604StandIn = 5

    /** The backend reading bookmarks will need: GET returns a slot's written `mushafId`, not its group's stand-in. */
    @Volatile var keepsMushafId = false

    /** `getRepresentativeMushafIdForGroup`: 7 and 6 for the IndoPak page groups, 5 (KFGQPC Hafs) for every other. */
    private fun representativeMushaf(group: String) = when (group) {
        "pages_548" -> 7
        "pages_610" -> 6
        "pages_604" -> pages604StandIn
        else -> 5
    }

    /** The `mushafId` GET renders for a slot: its group's stand-in, or the written one with [keepsMushafId]. */
    fun renderedMushafId(it: FReadingBookmark): Int? = if (keepsMushafId) it.mushafId else it.group?.let(::representativeMushaf)

    /** `readingBookmarkDTO`: every location field, null for a cleared slot, and nullable client timestamps. */
    private fun readingBookmarkData(it: FReadingBookmark) = buildJsonObject {
        put("slot", it.slot)
        put("name", it.name?.let(::JsonPrimitive) ?: JsonNull)
        put("mushafId", renderedMushafId(it)?.let(::JsonPrimitive) ?: JsonNull)
        put("type", it.type?.let(::JsonPrimitive) ?: JsonNull)
        put("group", it.group?.let(::JsonPrimitive) ?: JsonNull)
        put("key", it.key?.let(::JsonPrimitive) ?: JsonNull)
        put("verseNumber", it.verse?.let(::JsonPrimitive) ?: JsonNull)
        put("metadata", it.metadata ?: JsonNull)
        put("clientCreatedAt", it.createdAt?.let(::JsonPrimitive) ?: JsonNull)
        put("clientUpdatedAt", it.updatedAt?.let(::JsonPrimitive) ?: JsonNull)
    }

    companion object {
        const val DEFAULT_ID = "__default__"
        fun isSystemName(name: String): Boolean {
            val key = name.trim().lowercase()
            return key.startsWith("system:highlights:") && key.removePrefix("system:highlights:") in COLORS
        }
        /** collectionNameProtection.ts (red was renamed to pink in quran.com-users-backend#1927). */
        val COLORS = setOf("blue", "pink", "green", "yellow", "purple")
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/** Joi's client timestamp: an ISO string or epoch milliseconds, stored as a date. */
internal fun JsonObject.timestamp(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    val millis = primitive.longOrNull ?: Instant.parse(primitive.content).toEpochMilliseconds()
    return Instant.fromEpochMilliseconds(millis).toString()
}
