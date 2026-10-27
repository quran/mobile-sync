package com.quran.shared.syncv2.types

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.trimLikeServer
import com.quran.shared.syncv2.Change
import com.quran.shared.syncv2.Field
import com.quran.shared.syncv2.OutgoingMutation
import com.quran.shared.syncv2.Refs
import com.quran.shared.syncv2.RemoteEntry
import com.quran.shared.syncv2.RemoteItem
import com.quran.shared.syncv2.State
import com.quran.shared.syncv2.SyncType
import com.quran.shared.syncv2.db.SyncV2Database
import com.quran.shared.syncv2.epochMillis
import com.quran.shared.syncv2.get
import com.quran.shared.syncv2.getOrNull
import com.quran.shared.syncv2.int
import com.quran.shared.syncv2.isoTime
import com.quran.shared.syncv2.stateOf
import com.quran.shared.syncv2.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Where a slot points: `ayah` (key = sura, verse = ayah), or `page`, `surah` or `juz` (key only), and the mushaf it
 * carries ([mushafId]). A page carries the mushaf it was set in, or the server's stand-in after a sync with a backend
 * that doesn't keep it ([fromServer]); every other type carries [ServerMushaf.KFGQPC_HAFS]. A page keeps whatever
 * mushaf the server sends, one this library doesn't know included.
 */
data class ReadingLocation private constructor(
    val type: String,
    val key: Int,
    val verse: Int?,
    val mushafId: Int
) {
    companion object {
        fun ayah(sura: Int, ayah: Int) = of(AYAH, sura, ayah)
        fun page(page: Int, mushaf: ServerMushaf) = ReadingLocation(PAGE, page, null, mushaf.id)

        /**
         * A location as the server renders it. A page carries its [mushafId]; without one, the stand-in the server
         * answers with for its [group] (`getRepresentativeMushafIdForGroup`). Today's backend keeps only the group, so a
         * page it echoes carries that stand-in, not the mushaf it was set in.
         */
        fun fromServer(type: String, key: Int, verse: Int?, mushafId: Int?, group: String?): ReadingLocation =
            fromStored(type, key, verse, mushafId ?: groupStandIn(group).id)

        /** A location as it was stored; a page without a mushaf reads as the `pages_604` stand-in (5). */
        fun fromStored(type: String, key: Int, verse: Int?, mushafId: Int?): ReadingLocation =
            if (type == PAGE) ReadingLocation(PAGE, key, null, mushafId ?: groupStandIn(null).id) else of(type, key, verse)

        /** An ayah, surah or juz location: it carries [VERSE_STAND_IN]. */
        private fun of(type: String, key: Int, verse: Int?) = ReadingLocation(type, key, verse, VERSE_STAND_IN.id)

        private fun groupStandIn(group: String?): ServerMushaf = when (group) {
            "pages_610" -> ServerMushaf.INDOPAK_15_LINES
            "pages_548" -> ServerMushaf.INDOPAK_16_LINES
            else -> ServerMushaf.KFGQPC_HAFS
        }

        /**
         * Written for ayah (`verses_6236`), surah and juz (`default`) locations: the server picks those groups whatever
         * the id.
         */
        private val VERSE_STAND_IN = ServerMushaf.KFGQPC_HAFS
        private const val AYAH = "ayah"
        private const val PAGE = "page"
    }
}

data class ReadingBookmark(
    val localId: Long,
    val slot: Int,
    val name: String?,
    val location: ReadingLocation?,
    /** The server's opaque `metadata`, without the `source` it manages itself; kept through every write. */
    val metadata: JsonObject?,
    val createdAt: Long,
    val updatedAt: Long
)

/** Plain CRUD over `reading_bookmark`: one row per slot, which a clear keeps (with its name and metadata). */
class ReadingBookmarksRepository(private val database: SyncV2Database) {
    private val type = ReadingBookmarksType(database)

    fun readingBookmarks(): List<ReadingBookmark> = type.readAll().map { (localId, state) -> ReadingBookmarksType.toBookmark(localId, state) }

    fun set(slot: Int, location: ReadingLocation, at: Long) = write(slot, at) { it.copy(location = location) }

    /** The server trims names and stores an empty one as null; so does this. */
    fun rename(slot: Int, name: String?, at: Long) = write(slot, at) { it.copy(name = name?.trimLikeServer()?.ifEmpty { null }) }

    fun clear(slot: Int, at: Long) = write(slot, at) { it.copy(location = null) }

    private fun write(slot: Int, at: Long, change: (ReadingBookmark) -> ReadingBookmark) = database.transaction {
        val existing = readingBookmarks().firstOrNull { it.slot == slot }
        val next = change(existing ?: ReadingBookmark(0, slot, null, null, null, at, at))
        val state = ReadingBookmarksType.bookmarkState(next.slot, next.name, next.location, next.metadata, next.createdAt, at)
        if (existing == null) type.insert(state) else type.update(existing.localId, state)
    }
}

/**
 * READING_BOOKMARK: natural key = slot; the server upserts CREATEs by slot. Merged field by field: the location is
 * one field (type, key, verse and mushaf together), so two sides' locations never mix, and a rename on one device
 * survives a location change on another. A clear is an UPDATE with every location field null, as the backend
 * expects (its DELETE would also reset the slot's client timestamps). Entries without a slot (`{}`, a vanished row)
 * are skipped, never read as a clear. The mushaf key is `mushafId`, the only one this resource accepts: a page sends
 * the mushaf it carries, which is the one the server sent for a page set elsewhere. A page must fit in its mushaf's
 * pages ([ServerMushaf.pageCount]), as the server checks; a mushaf this library doesn't know is the server's to check.
 * `metadata` round-trips (the server replaces it on every write), except `source`, which the server keeps.
 */
class ReadingBookmarksType(private val database: SyncV2Database) : SyncType {
    private val queries get() = database.readingBookmarksQueries
    private val logger = Logger.withTag("SyncV2ReadingBookmarks")
    override val name = "READING_BOOKMARK"
    override val ignoredFields = setOf(CREATED_AT.name, UPDATED_AT.name)

    override fun naturalKey(state: State) = state[SLOT].toString()
    override fun clock(state: State) = state[UPDATED_AT]

    override fun readAll(): Map<Long, State> = queries.all().executeAsList().associate {
        val location = it.location_type?.let { type ->
            ReadingLocation.fromStored(type, it.location_key!!.toInt(), it.location_verse?.toInt(), it.mushaf_id?.toInt())
        }
        val metadata = it.metadata?.let { text -> Json.parseToJsonElement(text) as? JsonObject }
        it.local_id to bookmarkState(it.slot.toInt(), it.name, location, metadata, it.created_at, it.updated_at)
    }

    override fun insert(state: State, localId: Long?): Long {
        val l = location(state)
        val slot = state[SLOT].toLong()
        val name = state.getOrNull(NAME)
        val metadata = state.getOrNull(METADATA)
        if (localId != null) {
            queries.insertWithId(localId, slot, name, l?.type, l?.key?.toLong(), l?.verse?.toLong(), l?.mushafId?.toLong(), metadata, state[CREATED_AT], state[UPDATED_AT])
            return localId
        }
        queries.insert(slot, name, l?.type, l?.key?.toLong(), l?.verse?.toLong(), l?.mushafId?.toLong(), metadata, state[CREATED_AT], state[UPDATED_AT])
        return queries.lastInsertId().executeAsOne()
    }

    override fun update(localId: Long, state: State) {
        val l = location(state)
        queries.update(
            state[SLOT].toLong(), state.getOrNull(NAME), l?.type, l?.key?.toLong(), l?.verse?.toLong(), l?.mushafId?.toLong(),
            state.getOrNull(METADATA), state[CREATED_AT], state[UPDATED_AT], localId
        )
    }

    override fun park(localId: Long) {
        queries.park(localId)
    }

    override fun delete(localId: Long) {
        queries.delete(localId)
    }

    override fun fromRemote(entries: List<RemoteEntry>, refs: Refs): List<RemoteItem> =
        entries.filter { it.resource == name }.mapNotNull(::fromRemote)

    private fun fromRemote(entry: RemoteEntry): RemoteItem? {
        val id = entry.resourceId ?: return null
        if (entry.type == "DELETE") return RemoteItem.Deleted(id, entry.timestamp)
        val data = entry.data
        val slot = data.int("slot")?.takeIf { it in SLOTS } ?: run {
            logger.d { "Skipping reading bookmark $id: no slot in ${data.keys}" }
            return null
        }
        val type = data.string("type")
        val location = if (type == null) null else {
            val key = data.int("key")
            val verse = data.int("verseNumber")
            if (type !in TYPES || key == null || (type == AYAH) != (verse != null)) {
                return RemoteItem.Unresolved(id, "unsupported location $type $key $verse", entry.timestamp)
            }
            ReadingLocation.fromServer(type, key, verse, data.int("mushafId"), data.string("group"))
        }
        val updatedAt = data.epochMillis("clientUpdatedAt") ?: entry.timestamp
        val createdAt = data.epochMillis("clientCreatedAt") ?: updatedAt
        val metadata = data["metadata"] as? JsonObject
        return RemoteItem.Upsert(id, bookmarkState(slot, data.string("name"), location, metadata, createdAt, updatedAt), entry.timestamp)
    }

    override fun mutation(change: Change, refs: Refs): OutgoingMutation = when (change) {
        is Change.Create -> OutgoingMutation("CREATE", name, null, payload(change.state, create = true))
        is Change.Update -> OutgoingMutation("UPDATE", name, change.remoteId, payload(change.state, create = false))
        is Change.Delete -> OutgoingMutation("DELETE", name, change.remoteId, null)
    }

    override fun validate(change: Change): String? {
        if (change is Change.Delete) return null
        val state = change.state
        val location = location(state)
        return when {
            state[SLOT] !in SLOTS -> "INVALID_SLOT"
            (state.getOrNull(NAME)?.length ?: 0) > MAX_NAME_LENGTH -> "NAME_LENGTH"
            (metadata(state)?.size ?: 0) > MAX_METADATA_KEYS -> "METADATA_SIZE"
            location == null -> null
            location.type !in TYPES -> "INVALID_LOCATION"
            location.type == AYAH -> if (Verses.exists(location.key, location.verse ?: 0)) null else "INVALID_VERSE"
            location.type == PAGE -> {
                val pages = ServerMushaf.fromId(location.mushafId)?.pageCount ?: Int.MAX_VALUE
                if (location.key in 1..pages) null else "INVALID_PAGE"
            }
            location.key < 1 -> "INVALID_LOCATION"
            else -> null
        }
    }

    private fun payload(state: State, create: Boolean) = buildJsonObject {
        put("slot", state[SLOT])
        put("name", state.getOrNull(NAME)?.let(::JsonPrimitive) ?: JsonNull)
        val location = location(state)
        if (location == null) {
            listOf("mushafId", "type", "group", "key", "verseNumber").forEach { put(it, JsonNull) }
        } else {
            put("type", location.type)
            put("key", location.key)
            location.verse?.let { put("verseNumber", it) }
            put("mushafId", location.mushafId)
        }
        put("metadata", metadata(state) ?: JsonNull)
        if (create) put("clientCreatedAt", isoTime(state[CREATED_AT]))
        put("clientUpdatedAt", isoTime(state[UPDATED_AT]))
    }

    companion object {
        private val SLOT = Field.int("slot")
        private val NAME = Field.string("name")
        /** The whole location as one value, `type:key:verse:mushaf` (verse empty but for ayahs). */
        private val LOCATION = Field.string("location")
        /** `metadata` without `source`, as canonical JSON (sorted keys). */
        private val METADATA = Field.string("metadata")
        private val CREATED_AT = Field.long("createdAt")
        private val UPDATED_AT = Field.long("updatedAt")
        private val SLOTS = 1..3
        private const val AYAH = "ayah"
        private const val PAGE = "page"
        private val TYPES = setOf(AYAH, PAGE, "surah", "juz")
        private const val MAX_NAME_LENGTH = 255
        private const val MAX_METADATA_KEYS = 10
        /** Managed by the server, which keeps it whatever a client sends. */
        private const val SOURCE = "source"

        /** Every field is present, JSON null when empty, so a field merge can choose "cleared" or "unnamed". */
        fun bookmarkState(
            slot: Int,
            name: String?,
            location: ReadingLocation?,
            metadata: JsonObject?,
            createdAt: Long,
            updatedAt: Long
        ): State {
            val canonical = metadata?.filterKeys { it != SOURCE }?.takeIf { it.isNotEmpty() }
                ?.let { fields -> JsonObject(fields.entries.sortedBy { it.key }.associate { it.key to it.value }).toString() }
            return stateOf(
                SLOT(slot),
                name?.let { NAME(it) } ?: (NAME.name to JsonNull),
                location?.let { LOCATION("${it.type}:${it.key}:${it.verse ?: ""}:${it.mushafId}") } ?: (LOCATION.name to JsonNull),
                canonical?.let { METADATA(it) } ?: (METADATA.name to JsonNull),
                CREATED_AT(createdAt),
                UPDATED_AT(updatedAt)
            )
        }

        private fun location(state: State): ReadingLocation? = state.getOrNull(LOCATION)?.split(":")?.let { (type, key, verse, mushaf) ->
            ReadingLocation.fromStored(type, key.toInt(), verse.toIntOrNull(), mushaf.toInt())
        }

        private fun metadata(state: State): JsonObject? = state.getOrNull(METADATA)?.let { Json.parseToJsonElement(it) as JsonObject }

        internal fun toBookmark(localId: Long, state: State) = ReadingBookmark(
            localId, state[SLOT], state.getOrNull(NAME), location(state), metadata(state), state[CREATED_AT], state[UPDATED_AT]
        )
    }
}
