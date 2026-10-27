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
 * Where a slot points: `ayah` (key = sura, verse = ayah), or `page`, `surah` or `juz` (key only). The server keeps
 * only the bookmark group, which the mushaf picks, and answers with the group and a stand-in mushaf for it.
 *
 * A page carries its server [group] and that group's stand-in mushaf ([PageGroup.standIn]). A group this
 * library doesn't know is kept as the server named it, with the mushaf the server sent; [ReadingBookmarksType.validate]
 * holds any write of it. Every other type uses [STANDARD] and has no group.
 */
data class ReadingLocation private constructor(
    val type: String,
    val key: Int,
    val verse: Int?,
    val mushafId: Int,
    /** A page's server group (`pages_604`, `pages_610`, `pages_548` or one this library doesn't know); null otherwise. */
    val group: String?
) {
    /** A page's known group, or null for another type or an unknown group. */
    val pageGroup: PageGroup? get() = group?.let(PageGroup::named)

    companion object {
        /** A location written with [mushaf]: a page goes to the group the server picks for that mushaf. */
        fun of(type: String, key: Int, verse: Int? = null, mushaf: ServerMushaf = STANDARD): ReadingLocation =
            if (type == PAGE) page(key, mushaf.pageGroup) else ReadingLocation(type, key, verse, STANDARD.id, null)

        fun ayah(sura: Int, ayah: Int, mushaf: ServerMushaf = STANDARD) = of(AYAH, sura, ayah, mushaf)
        fun page(page: Int, mushaf: ServerMushaf = STANDARD) = of(PAGE, page, null, mushaf)
        fun page(page: Int, group: PageGroup) = ReadingLocation(PAGE, page, null, group.standIn.id, group.serverName)

        /**
         * A location as the server renders it, or as it was stored: a page by its [group] when there is one, else by
         * the group its [mushafId] picks. An unknown group keeps its name and the server's mushaf.
         */
        fun fromServer(type: String, key: Int, verse: Int?, mushafId: Int?, group: String?): ReadingLocation {
            if (type != PAGE) return of(type, key, verse)
            val known = if (group == null) ServerMushaf.fromId(mushafId ?: STANDARD.id)?.pageGroup ?: PageGroup.PAGES_604 else PageGroup.named(group)
            return if (known != null) page(key, known) else ReadingLocation(PAGE, key, null, mushafId ?: STANDARD.id, group)
        }

        /** The stand-in for `verses_6236`, `pages_604` and `default` (surah and juz). */
        val STANDARD = ServerMushaf.KFGQPC_HAFS
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
 * are skipped, never read as a clear. The mushaf key is `mushafId`, the only one this resource accepts; a page is read
 * by its `group` ([ReadingLocation.fromServer]) and must fit in it ([PageGroup.pageCount]), as the server checks. A
 * page of a group this library doesn't know is held (`UNKNOWN_PAGE_GROUP`) on any create or update: the server picks
 * the group from `mushafId` alone and would file it under `pages_604`, so a rename must not resend it.
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
            ReadingLocation.fromServer(type, it.location_key!!.toInt(), it.location_verse?.toInt(), it.mushaf_id?.toInt(), it.location_group)
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
            queries.insertWithId(localId, slot, name, l?.type, l?.key?.toLong(), l?.verse?.toLong(), l?.mushafId?.toLong(), l?.group, metadata, state[CREATED_AT], state[UPDATED_AT])
            return localId
        }
        queries.insert(slot, name, l?.type, l?.key?.toLong(), l?.verse?.toLong(), l?.mushafId?.toLong(), l?.group, metadata, state[CREATED_AT], state[UPDATED_AT])
        return queries.lastInsertId().executeAsOne()
    }

    override fun update(localId: Long, state: State) {
        val l = location(state)
        queries.update(
            state[SLOT].toLong(), state.getOrNull(NAME), l?.type, l?.key?.toLong(), l?.verse?.toLong(), l?.mushafId?.toLong(),
            l?.group, state.getOrNull(METADATA), state[CREATED_AT], state[UPDATED_AT], localId
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
            location.type == PAGE -> when (val group = location.pageGroup) {
                null -> "UNKNOWN_PAGE_GROUP"
                else -> if (location.key in 1..group.pageCount) null else "INVALID_PAGE"
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
        /** The whole location as one value, `type:key:verse:mushaf:group` (verse empty but for ayahs, group but for pages). */
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
                location?.let { LOCATION("${it.type}:${it.key}:${it.verse ?: ""}:${it.mushafId}:${it.group ?: ""}") } ?: (LOCATION.name to JsonNull),
                canonical?.let { METADATA(it) } ?: (METADATA.name to JsonNull),
                CREATED_AT(createdAt),
                UPDATED_AT(updatedAt)
            )
        }

        private fun location(state: State): ReadingLocation? = state.getOrNull(LOCATION)?.split(":", limit = 5)?.let { parts ->
            val (type, key, verse, mushaf) = parts
            ReadingLocation.fromServer(type, key.toInt(), verse.toIntOrNull(), mushaf.toInt(), parts.getOrNull(4)?.ifEmpty { null })
        }

        private fun metadata(state: State): JsonObject? = state.getOrNull(METADATA)?.let { Json.parseToJsonElement(it) as JsonObject }

        internal fun toBookmark(localId: Long, state: State) = ReadingBookmark(
            localId, state[SLOT], state.getOrNull(NAME), location(state), metadata(state), state[CREATED_AT], state[UPDATED_AT]
        )
    }
}
