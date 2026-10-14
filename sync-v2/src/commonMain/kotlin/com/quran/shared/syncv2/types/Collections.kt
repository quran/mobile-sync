package com.quran.shared.syncv2.types

import com.quran.shared.mutations.ServerTextLimit
import com.quran.shared.mutations.collectionNameKey
import com.quran.shared.mutations.escapeServerText
import com.quran.shared.mutations.trimLikeServer
import com.quran.shared.mutations.unescapeServerText
import com.quran.shared.syncv2.Change
import com.quran.shared.syncv2.Field
import com.quran.shared.syncv2.OutgoingMutation
import com.quran.shared.syncv2.Refs
import com.quran.shared.syncv2.RemoteEntry
import com.quran.shared.syncv2.RemoteItem
import com.quran.shared.syncv2.State
import com.quran.shared.syncv2.SyncType
import com.quran.shared.syncv2.bool
import com.quran.shared.syncv2.db.SyncV2Database
import com.quran.shared.syncv2.epochMillis
import com.quran.shared.syncv2.get
import com.quran.shared.syncv2.getOrNull
import com.quran.shared.syncv2.isoTime
import com.quran.shared.syncv2.stateOf
import com.quran.shared.syncv2.string
import com.quran.shared.syncv2.with
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** [isSystem]: the server's flag (Favorites, highlights). [isReadOnly]: no rename or delete (system or older-app colors). */
data class Collection(val localId: Long, val name: String, val isDefault: Boolean, val isSystem: Boolean, val isReadOnly: Boolean)

/**
 * Plain CRUD over `collection` and `collection_ayah` for this module's tests, standing in for the app's repositories
 * (persistence). Favorites is a normal local row with `is_default = 1`. The companion's [ensureFavorites] is
 * production API: the app's schema seeds Favorites with it, and [CollectionsType.wipe] seeds it again.
 */
class CollectionsRepository(private val database: SyncV2Database) {
    private val queries get() = database.collectionsQueries

    init {
        ensureFavorites(database)
    }

    fun collections(): List<Collection> = queries.collectionsAll().executeAsList()
        .map { Collection(it.local_id, it.name, it.is_default == 1L, it.is_system == 1L, it.is_read_only == 1L) }

    fun favorites(): Collection = collections().single { it.isDefault }

    fun byName(name: String): Collection? = collections().firstOrNull { !it.isDefault && it.name == name }

    fun addCollection(name: String, at: Long): Collection {
        val trimmed = name.trimLikeServer()
        require(queries.collectionByKey(collectionNameKey(trimmed)).executeAsOneOrNull() == null) { "name taken" }
        queries.collectionInsert(trimmed, collectionNameKey(trimmed), 0, 0, 0, at, at)
        return collections().last()
    }

    fun renameCollection(localId: Long, name: String, at: Long) {
        val row = queries.collectionGet(localId).executeAsOne()
        require(row.is_default == 0L && row.is_read_only == 0L) { "read-only collection" }
        val trimmed = name.trimLikeServer()
        queries.collectionUpdate(trimmed, collectionNameKey(trimmed), row.is_system, row.is_read_only, at, localId)
    }

    fun deleteCollection(localId: Long) = database.transaction {
        val row = queries.collectionGet(localId).executeAsOne()
        require(row.is_default == 0L && row.is_read_only == 0L) { "read-only collection" }
        queries.collectionAyahsDeleteIn(localId)
        queries.collectionDelete(localId)
    }

    fun addAyah(collectionLocalId: Long, sura: Int, ayah: Int, at: Long) {
        if (queries.collectionAyahFind(collectionLocalId, sura.toLong(), ayah.toLong()).executeAsOneOrNull() != null) return
        queries.collectionAyahInsert(collectionLocalId, sura.toLong(), ayah.toLong(), at)
    }

    fun removeAyah(collectionLocalId: Long, sura: Int, ayah: Int) {
        queries.collectionAyahFind(collectionLocalId, sura.toLong(), ayah.toLong()).executeAsOneOrNull()
            ?.let { queries.collectionAyahDelete(it.local_id) }
    }

    fun ayahs(collectionLocalId: Long): List<Pair<Int, Int>> =
        queries.collectionAyahsIn(collectionLocalId).executeAsList().map { it.sura.toInt() to it.ayah.toInt() }

    companion object {
        /** Production API: seeds the Favorites row when it is missing (a new database, or after a wipe). */
        fun ensureFavorites(database: SyncV2Database) {
            if (database.collectionsQueries.collectionDefault().executeAsOneOrNull() == null) {
                database.collectionsQueries.collectionInsert("Favorites", null, 1, 1, 1, 0, 0)
            }
        }
    }
}

/**
 * COLLECTION: natural key = `lower(trim(name))`. System and older-app color collections are read-only (from GET data);
 * only the server's `isSystem` ones are system collections, which app-facing lists hide.
 * Names are sent escaped like the server's sanitize-html (so text that looks like a tag survives) and unescaped on GET.
 * Favorites is a local row whose state is fixed, with a key no name can have; the server's virtual `__default__`
 * collection maps to the same state, so the engine links the two like any other item and never pushes Favorites.
 */
class CollectionsType(private val database: SyncV2Database) : SyncType {
    private val queries get() = database.collectionsQueries
    override val name = "COLLECTION"
    override val ignoredFields = setOf(UPDATED_AT.name, CREATED_AT.name)
    override val forbidsItems = true

    override fun naturalKey(state: State) = if (isFavorites(state)) FAVORITES_KEY else collectionNameKey(state[NAME])
    override fun clock(state: State) = state[UPDATED_AT]

    override fun readAll(): Map<Long, State> = queries.collectionsAll().executeAsList().associate {
        it.local_id to if (it.is_default == 1L) {
            FAVORITES
        } else {
            stateOf(NAME(it.name), SYSTEM(it.is_system == 1L), READ_ONLY(it.is_read_only == 1L), UPDATED_AT(it.updated_at), CREATED_AT(it.created_at))
        }
    }

    override fun insert(state: State, localId: Long?): Long {
        if (isFavorites(state)) return queries.collectionDefault().executeAsOne().local_id
        val name = state[NAME]
        val system = if (state[SYSTEM]) 1L else 0L
        val readOnly = if (readOnly(state)) 1L else 0L
        val createdAt = createdAt(state)
        if (localId != null) {
            queries.collectionInsertWithId(localId, name, naturalKey(state), 0, system, readOnly, createdAt, clock(state))
            return localId
        }
        queries.collectionInsert(name, naturalKey(state), 0, system, readOnly, createdAt, clock(state))
        return queries.lastInsertId().executeAsOne()
    }

    override fun update(localId: Long, state: State) {
        if (isFavorites(state)) return
        queries.collectionUpdate(state[NAME], naturalKey(state), if (state[SYSTEM]) 1L else 0L, if (readOnly(state)) 1L else 0L, clock(state), localId)
    }

    override fun park(localId: Long) {
        queries.collectionPark("\u0000park:$localId", localId)
    }

    override fun delete(localId: Long) {
        queries.collectionAyahsDeleteIn(localId)
        queries.collectionDelete(localId)
    }

    override fun wipe() {
        queries.collectionAyahsDeleteAll()
        queries.collectionsDeleteAll()
        CollectionsRepository.ensureFavorites(database)
    }

    override fun fromRemote(entries: List<RemoteEntry>, refs: Refs): List<RemoteItem> =
        entries.filter { it.resource == name }.mapNotNull(::fromRemote)

    private fun fromRemote(entry: RemoteEntry): RemoteItem? {
        val id = entry.resourceId ?: return null
        if (id == DEFAULT_COLLECTION_ID) return if (entry.type == "DELETE") null else RemoteItem.Upsert(id, FAVORITES, entry.timestamp)
        if (entry.type == "DELETE") return RemoteItem.Deleted(id, entry.timestamp)
        if (entry.data.bool("isDefault") == true) return null
        val name = entry.data.string("name")?.unescapeServerText() ?: return null
        val source = (entry.data["metadata"] as? JsonObject)?.string("source")
        val system = entry.data.bool("isSystem") == true
        val readOnly = system || (source in PROTECTED_SOURCES && collectionNameKey(name) in PROTECTED_COLORS)
        val updatedAt = entry.data.epochMillis("clientUpdatedAt") ?: entry.timestamp
        val createdAt = entry.data.epochMillis("clientCreatedAt") ?: updatedAt
        return RemoteItem.Upsert(
            id, stateOf(NAME(name), SYSTEM(system), READ_ONLY(readOnly), UPDATED_AT(updatedAt), CREATED_AT(createdAt)), entry.timestamp
        )
    }

    /** Favorites waits for the GET to link it to `__default__`, which the server never lets a client mutate. */
    override fun mutation(change: Change, refs: Refs): OutgoingMutation? = when (change) {
        is Change.Create -> if (isFavorites(change.state)) null else {
            OutgoingMutation("CREATE", name, null, buildJsonObject {
                put("name", change.state[NAME].escapeServerText())
                put("clientCreatedAt", isoTime(createdAt(change.state)))
                put("clientUpdatedAt", isoTime(clock(change.state)))
            })
        }
        is Change.Update -> OutgoingMutation("UPDATE", name, change.remoteId, buildJsonObject {
            put("name", change.state[NAME].escapeServerText())
            put("clientUpdatedAt", isoTime(clock(change.state)))
        })
        is Change.Delete -> OutgoingMutation("DELETE", name, change.remoteId, null)
    }

    /**
     * The server's collectionNameProtection: a client may create `system:highlights:<color>` (a highlight), but no
     * system or older-app color collection may be renamed or deleted, and no other collection may take a system name.
     */
    override fun validate(change: Change): String? {
        if (isFavorites(change.state)) return null
        return when (change) {
            is Change.Delete -> "READ_ONLY".takeIf { readOnly(change.base) }
            is Change.Update -> when {
                readOnly(change.base) -> "READ_ONLY"
                collectionNameKey(change.state[NAME]).startsWith(SYSTEM_PREFIX) -> "RESERVED_NAME"
                else -> nameLength(change.state)
            }
            is Change.Create -> {
                val key = collectionNameKey(change.state[NAME])
                if (key.startsWith(SYSTEM_PREFIX) && !isHighlight(key)) "RESERVED_NAME" else nameLength(change.state)
            }
        }
    }

    private fun nameLength(state: State) = "NAME_LENGTH".takeUnless { ServerTextLimit.COLLECTION_NAME.accepts(state[NAME]) }

    private fun isHighlight(key: String) = key.startsWith(HIGHLIGHTS_PREFIX) && key.removePrefix(HIGHLIGHTS_PREFIX) in PROTECTED_COLORS

    override fun disambiguate(state: State, n: Int): State = state.with(NAME("${state[NAME]} ($n)"))

    companion object {
        const val DEFAULT_COLLECTION_ID = "__default__"
        private val NAME = Field.string("name")
        private val SYSTEM = Field.bool("system")
        private val READ_ONLY = Field.bool("readOnly")
        private val UPDATED_AT = Field.long("updatedAt")
        /** When the collection was created (`clientCreatedAt`): app-facing lists order by it; not data. */
        private val CREATED_AT = Field.long("createdAt")
        private val DEFAULT = Field.bool("default")

        /** The state of Favorites on both sides; it never changes. */
        private val FAVORITES = stateOf(NAME("Favorites"), SYSTEM(true), DEFAULT(true), UPDATED_AT(0))
        private const val FAVORITES_KEY = "\u0000favorites"

        private fun isFavorites(state: State) = state.getOrNull(DEFAULT) == true
        private fun readOnly(state: State) = state.getOrNull(READ_ONLY) ?: state[SYSTEM]
        private fun createdAt(state: State) = state.getOrNull(CREATED_AT) ?: state[UPDATED_AT]

        /**
         * A collection's state for a local write: [system] and [readOnly] are set for the highlight collections the app
         * creates (`system:highlights:<color>`), as the server's GET reports them.
         */
        fun collectionState(name: String, updatedAt: Long, system: Boolean = false, readOnly: Boolean = system): State =
            stateOf(NAME(name), SYSTEM(system), READ_ONLY(readOnly), UPDATED_AT(updatedAt))
        private val PROTECTED_SOURCES = setOf("QDC_ANDROID", "QDC_IOS")
        private val PROTECTED_COLORS = setOf("blue", "pink", "green", "yellow", "purple")
        private const val SYSTEM_PREFIX = "system:"
        private const val HIGHLIGHTS_PREFIX = "system:highlights:"
    }
}
