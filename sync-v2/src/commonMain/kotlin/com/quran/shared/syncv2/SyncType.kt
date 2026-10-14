package com.quran.shared.syncv2

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** An item's canonical state: the same shape for working (domain row), base and remote, so the merge is generic. */
typealias State = JsonObject

/** One entry of `GET /v1/sync`: the current data of one changed server resource. */
data class RemoteEntry(
    val resource: String,
    val resourceId: String?,
    val type: String,
    val data: JsonObject,
    val timestamp: Long
)

/** What a [SyncType] makes of a [RemoteEntry]. */
sealed interface RemoteItem {
    val remoteId: String
    val timestamp: Long

    data class Upsert(override val remoteId: String, val state: State, override val timestamp: Long) : RemoteItem
    data class Deleted(override val remoteId: String, override val timestamp: Long) : RemoteItem
}

/** A mutation the engine POSTs. [resourceId] is null for creates and for key-based deletes. */
data class OutgoingMutation(
    val type: String,
    val resource: String,
    val resourceId: String?,
    val data: JsonObject?
)

/** The server's answer to one POSTed mutation (it echoes the request data, not the stored state). */
data class PostedMutation(
    val type: String,
    val resource: String,
    val resourceId: String?,
    val data: JsonElement?,
    val timestamp: Long?
)

/**
 * A child type's reference to its parent: the state field that holds the parent's local id.
 *
 * One level by design: no synced type has a grandparent (verses in collections are the only children; notes,
 * sessions and reading bookmarks have no parent). Restoring cascaded children and the "waiting on" list recurse;
 * parent-covers-child, re-pointing children on a link, dirty parents and remote child edits look one level down
 * only. A deeper chain would need those extended and tested first.
 */
data class ParentRef(val field: Field<Long>, val type: SyncType)

/** Resolves local ids to remote ids and back, for references between types. */
interface Refs {
    fun remoteId(type: SyncType, localId: Long): String?
    fun localId(type: SyncType, remoteId: String): Long?
}

/** One local change to push, `diff(base, working)` for one item. */
sealed interface Change {
    /** What the server holds once it lands: working for CREATE and UPDATE, base for DELETE. */
    val state: State

    data class Create(override val state: State) : Change
    data class Update(val remoteId: String, val base: State, override val state: State) : Change
    data class Delete(val remoteId: String, val base: State) : Change {
        override val state: State get() = base
    }
}

/**
 * The per-type customization. Everything else (merge, uniqueness repair, push, errors, outbox) is generic.
 * A type reads and writes its plain domain table and maps its [State] to and from the server.
 * Only [name], the domain CRUD, [fromRemote] and [mutation] have no default.
 */
interface SyncType {
    /** The `sync_item.resource` key. */
    val name: String
    val parent: ParentRef? get() = null
    /** State fields that are not data (for example a timestamp only used to break ties). */
    val ignoredFields: Set<String> get() = emptySet()
    /**
     * When both sides changed an item, the newer side wins as a whole instead of field by field, and a clashing key
     * goes to the newer item. For states whose fields only make sense together (a session's sura and ayah).
     */
    val mergeWholeObject: Boolean get() = false

    /**
     * The identity the server upserts on and keeps unique (a verse, a collection name), or null when the type has none
     * (notes). It drives natural-key revival, linking, uniqueness repair, and outbox matching (with the content).
     */
    fun naturalKey(state: State): String? = null
    /**
     * A client identity of one create that the server keeps and no other device produces, for types without a
     * natural key (notes: `(clientCreatedAt, ranges)`; the body is left out). A lost CREATE is recognised by it alone,
     * even after another device edited the item. Null: matched by natural key and content, or pushed again.
     */
    fun outboxKey(state: State): String? = null
    /** The client timestamp used when both sides changed the same field. */
    fun clock(state: State): Long = 0L

    // Domain table. Local ids may be NEGATIVE: the engine restores a remote item it only knew as a tombstone under
    // a negative id (see SyncStore.newTombstoneId). Tables, insert, park and every key derived from an id must accept
    // that: a port must not assume local ids are positive.
    fun readAll(): Map<Long, State>
    /** Inserts a row; [localId] (possibly negative) is given when restoring a deleted row (edit beats delete). */
    fun insert(state: State, localId: Long? = null): Long
    fun update(localId: Long, state: State)
    /**
     * Moves the row's natural key out of the way, so a chain of key changes can't collide halfway. The parked key
     * must never be a real one, nor another row's parked key, for any local id, negative included (`ayah = -2 * id - 1`
     * for `id >= 0`, `2 * id` below 0; not `-local_id`, which parks -1 on ayah 1, nor `-abs(id) - 1`, which parks 1 and -1 together).
     */
    fun park(localId: Long) {}
    fun delete(localId: Long)
    fun wipe() = readAll().keys.forEach(::delete)

    // Server mapping.
    /**
     * This type's items in a GET: [entries] holds every resource, so a type can also read resources it never pushes.
     * Must tolerate `{}` data and skip unsupported kinds.
     */
    fun fromRemote(entries: List<RemoteEntry>, refs: Refs): List<RemoteItem>
    /** Null defers the change to a later phase (a parent has no remote id yet), or skips it (no UPDATE). */
    fun mutation(change: Change, refs: Refs): OutgoingMutation?
    /** The remote id a successful CREATE binds to. */
    fun bindCreated(state: State, response: PostedMutation): String? = response.resourceId?.takeIf { it.isNotEmpty() }

    // Policy.
    /** Why the server would refuse [change] (limits, reserved names): it is held instead, or undone for a DELETE. */
    fun validate(change: Change): String? = null
    /** A unique variant of [state] for the n-th clash ("Study (2)"); null drops the local row. */
    fun disambiguate(state: State, n: Int): State? = null
    /**
     * Runs after the merge and before the push. [synced] is the state the server has for each synced item (its base),
     * by local id: a policy that ranks items must rank synced ones by it, so every device ranks the same.
     */
    fun prune(synced: Map<Long, State>) {}
}
