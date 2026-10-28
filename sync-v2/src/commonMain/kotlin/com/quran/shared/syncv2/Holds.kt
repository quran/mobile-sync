package com.quran.shared.syncv2

/**
 * The natural keys that held items still have on the server (their base), by owner. A change taking one of them
 * would fail the unique check, so it waits until the held item moves (see [PushPlanner]).
 */
internal fun heldKeyOwners(type: SyncType, rows: Map<Long, SyncRow>): Map<String, Long> =
    rows.values.filter { it.heldReason != null && it.base != null }
        .mapNotNull { row -> type.naturalKey(row.base!!)?.let { it to row.localId } }.toMap()

/** An item that does not sync: held itself, or waiting on a held parent ([waitingOn] names that parent). */
data class HeldItem(val type: String, val localId: Long, val reason: String, val waitingOn: ItemRef? = null) {
    companion object {
        const val WAITING_ON_PARENT = "WAITING_ON_PARENT"
        /** An item its type never pushes, which only a GET can link (Favorites to `__default__`), and none has. */
        const val NEVER_LINKED = "NEVER_LINKED"
        /** A change that takes a natural key a held item still has on the server (the other half of a swap). */
        const val WAITING_ON_KEY = "WAITING_ON_KEY"
    }
}

data class ItemRef(val type: String, val localId: Long)

/**
 * Whether this tombstone's hold is a held DELETE: it holds the base, as the server refused the delete. A tombstone
 * held with any other state was held for a create or edit the user deleted since, so the hold no longer applies.
 */
internal fun SyncRow.holdsDelete(merger: Merger): Boolean = heldReason != null && base != null && merger.same(heldState, base)

/** Releasing and listing holds. A hold is also released when the user edits or deletes the item (see [PushPlanner]). */
class Holds(private val store: SyncStore, private val types: List<SyncType>, private val refs: Refs) {

    /**
     * Releases holds that expired, and every hold when the app or library version changed. Forgets the failure
     * counts of items that are gone (no domain row and no sync row).
     */
    fun review(version: String, now: Long) {
        val failures = store.failures
        if (failures.isNotEmpty()) {
            val live = types.flatMap { type -> (type.readAll().keys + store.rows(type.name).keys).map { "${type.name}:$it" } }.toSet()
            store.failures = failures.filterKeys { it in live }
        }
        val versionChanged = store.version != version
        types.forEach { type ->
            store.rows(type.name).values
                .filter { it.heldReason != null && (versionChanged || (it.heldUntil != null && it.heldUntil <= now)) }
                .forEach { store.put(type.name, it.unheld()) }
        }
        store.version = version
    }

    /**
     * Held items, then the local changes waiting on them: a child whose parent is held before the server ever saw
     * it can't be pushed. [HeldItem.waitingOn] is the held item at the root, so a UI can say "N items waiting on X".
     *
     * It reads the items as the next push would plan them (see [current]), so it changes as soon as the user edits or
     * deletes an item. Never-linked items are listed only once an account is signed in.
     */
    fun list(): List<HeldItem> {
        val working = types.associateWith { it.readAll() }
        val rows = types.associateWith { type -> current(type, store.rows(type.name), working.getValue(type)) }
        val neverLinked = types.associateWith { type -> neverLinked(type, rows.getValue(type), working.getValue(type)) }
        val held = types.flatMap { type ->
            rows.getValue(type).values.mapNotNull { row -> row.heldReason?.let { HeldItem(type.name, row.localId, it) } } +
                neverLinked.getValue(type).map { HeldItem(type.name, it, HeldItem.NEVER_LINKED) }
        }
        // Per type, the items that can't reach the server, mapped to the held item they wait on.
        val blocked = mutableMapOf<SyncType, Map<Long, ItemRef>>()
        val waiting = mutableListOf<HeldItem>()
        types.forEach { type ->
            val typeRows = rows.getValue(type)
            val own = (typeRows.values.filter { it.heldReason != null && it.remoteId == null }.map { it.localId } + neverLinked.getValue(type))
                .associate { it to ItemRef(type.name, it) }
            val parent = type.parent
            val inherited = if (parent == null) emptyMap() else {
                val parentBlocked = blocked[parent.type].orEmpty()
                val merger = Merger(type)
                working.getValue(type).mapNotNull { (localId, state) ->
                    val root = parentId(state, parent)?.let(parentBlocked::get) ?: return@mapNotNull null
                    if (typeRows[localId]?.heldReason != null || merger.same(state, typeRows[localId]?.base)) return@mapNotNull null
                    waiting += HeldItem(type.name, localId, HeldItem.WAITING_ON_PARENT, root)
                    localId to root
                }.toMap()
            }
            blocked[type] = own + inherited
        }
        val waitingOnKey = types.flatMap { type ->
            val typeRows = rows.getValue(type)
            val owners = heldKeyOwners(type, typeRows)
            if (owners.isEmpty()) return@flatMap emptyList()
            val merger = Merger(type)
            working.getValue(type).mapNotNull { (localId, state) ->
                val owner = type.naturalKey(state)?.let(owners::get)?.takeIf { it != localId } ?: return@mapNotNull null
                if (typeRows[localId]?.heldReason != null || merger.same(state, typeRows[localId]?.base)) return@mapNotNull null
                HeldItem(type.name, localId, HeldItem.WAITING_ON_KEY, ItemRef(type.name, owner))
            }
        }
        return held + waiting + waitingOnKey
    }

    /**
     * [type]'s sync rows as the next push sees them. [PushPlanner] releases a hold whose item was edited since, then
     * validates the edit and holds it again, with the new reason, if the server would still refuse it; the next merge
     * forgets a never-synced held item the user deleted. A held DELETE stays held; a hold on an edit the user then
     * deleted is released, as the planner pushes the delete.
     */
    private fun current(type: SyncType, rows: Map<Long, SyncRow>, working: Map<Long, State>): Map<Long, SyncRow> {
        val merger = Merger(type)
        val heldKeys = heldKeyOwners(type, rows)
        return rows.mapValues { (localId, row) ->
            val state = working[localId]
            when {
                row.heldReason == null -> row
                state == null -> if (row.holdsDelete(merger)) row else row.unheld()
                merger.same(row.heldState, state) -> row
                else -> planItem(type, merger, localId, row, state, heldKeys)?.second ?: row.unheld()
            }
        }
    }

    /** New items with no parent to wait for, whose type defers their CREATE: only a link from a GET can bind them. */
    private fun neverLinked(type: SyncType, rows: Map<Long, SyncRow>, working: Map<Long, State>): List<Long> {
        if (store.account == null) return emptyList()
        return working.filter { (localId, state) ->
            val row = rows[localId]
            row?.base == null && row?.heldReason == null && !waitsForParent(type, state, refs) &&
                type.mutation(Change.Create(state), refs) == null
        }.keys.toList()
    }
}
