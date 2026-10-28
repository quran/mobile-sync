package com.quran.shared.syncv2

/**
 * A [Change] of one item, with the mutation its type maps it to. [holdState] is the item's working state, which a
 * hold records: it differs from [state] only for a step towards it (a rename to a temporary key in a swap).
 */
data class Planned(
    val type: SyncType,
    val localId: Long,
    val change: Change,
    val mutation: OutgoingMutation,
    val holdState: State = change.state
) {
    val kind: String get() = when (change) {
        is Change.Create -> PushPlanner.CREATE
        is Change.Update -> PushPlanner.UPDATE
        is Change.Delete -> PushPlanner.DELETE
    }
    val state: State get() = change.state
    val remoteId: String? get() = (change as? Change.Update)?.remoteId ?: (change as? Change.Delete)?.remoteId
}

/** What [PushPlanner.plan] sends now ([planned]), and the items it [deferred] to a later phase for their parent. */
internal class PushPlan(val planned: List<Planned>, val deferred: List<ItemRef>)

/**
 * Computes the local "commit" as `diff(base, working)` per item. Deletes come first, then updates (renames ordered by
 * [orderKeyChanges]), then creates.
 * Items whose parent has no remote id yet are deferred to the next phase; a child DELETE that its parent's
 * DELETE covers is dropped; invalid items are held, together with their dependents (which then stay deferred).
 */
class PushPlanner(private val store: SyncStore, private val types: List<SyncType>, private val refs: Refs) {

    fun plan(): List<Planned> = plan(preview = false).planned

    /**
     * What [plan] would plan now, with the items it defers for their parent's remote id, without its writes. [plan]
     * queues its writes and applies them in order once every decision is made, so no decision reads a write of the
     * same pass, and a preview, which drops the queue, plans exactly what [plan] plans.
     */
    internal fun preview(): PushPlan = plan(preview = true)

    /** [plan] with the deferred items; with [preview], without its writes (see [preview]). */
    internal fun plan(preview: Boolean): PushPlan {
        val writes = mutableListOf<() -> Unit>()
        fun write(block: () -> Unit) {
            writes += block
        }
        val deletes = mutableListOf<Planned>()
        val updates = mutableListOf<Planned>()
        val creates = mutableListOf<Planned>()
        val deferred = mutableListOf<ItemRef>()
        val workingByType = types.associateWith { it.readAll() }
        fun defer(type: SyncType, localId: Long, change: Change) {
            if (waitsForParent(type, change.state, refs)) deferred += ItemRef(type.name, localId)
        }
        types.forEach { type ->
            val merger = Merger(type)
            val rows = store.rows(type.name)
            val working = workingByType.getValue(type)
            val typeUpdates = mutableListOf<Planned>()
            val heldKeys = heldKeyOwners(type, rows)
            rows.values.filter { it.localId !in working && it.base != null && it.remoteId != null }.forEach { row ->
                if (row.heldReason != null) {
                    if (row.holdsDelete(merger)) return@forEach
                    // Held for an edit the user then deleted: the delete is a new change.
                    write { store.put(type.name, row.unheld()) }
                }
                if (coveredByParent(type, row, workingByType, ::write)) return@forEach
                val change = Change.Delete(row.remoteId!!, row.base!!)
                // A delete the server would refuse is undone, like a 403 on DELETE.
                if (type.validate(change) != null) return@forEach write { restore(type, row.localId, row.base) }
                val mutation = type.mutation(change, refs) ?: return@forEach defer(type, row.localId, change)
                deletes += Planned(type, row.localId, change, mutation)
            }
            working.forEach { (localId, state) ->
                val row = rows[localId]
                if (row?.heldReason != null) {
                    if (merger.same(row.heldState, state)) return@forEach
                    write { store.put(type.name, row.unheld()) }
                }
                val (change, hold) = planItem(type, merger, localId, row, state, heldKeys) ?: return@forEach
                if (hold != null) {
                    write { store.put(type.name, hold) }
                    return@forEach
                }
                val mutation = type.mutation(change, refs) ?: return@forEach defer(type, localId, change)
                if (change is Change.Create) creates += Planned(type, localId, change, mutation) else typeUpdates += Planned(type, localId, change, mutation)
            }
            val taken = (working.values + rows.values.mapNotNull { it.base }).mapNotNull(type::naturalKey).toSet()
            updates += orderKeyChanges(type, typeUpdates, taken, refs)
        }
        if (!preview) writes.forEach { it() }
        return PushPlan(deletes + updates + creates, deferred)
    }

    /** A child tombstone whose parent is deleted too: the parent's DELETE cascades on the server. */
    private fun coveredByParent(
        type: SyncType,
        row: SyncRow,
        working: Map<SyncType, Map<Long, State>>,
        write: (() -> Unit) -> Unit
    ): Boolean {
        val parent = type.parent ?: return false
        val parentId = parentId(row.base!!, parent) ?: return false
        if (parentId in working.getValue(parent.type)) return false
        if (store.row(parent.type.name, parentId) == null) write { store.remove(type.name, row.localId) }
        return true
    }

    /** Undoes a local delete the server refused: the parent and the children its delete cascaded come back. */
    fun restore(type: SyncType, localId: Long, base: State) {
        type.insert(base, localId)
        types.filter { it.parent?.type === type }.forEach { child ->
            val working = child.readAll()
            store.rows(child.name).values
                .filter { it.base != null && it.localId !in working && parentId(it.base, child.parent!!) == localId }
                .forEach { restore(child, it.localId, it.base!!) }
        }
    }

    /** After a parent DELETE landed, its children's tombstones are settled too. */
    fun forgetCoveredChildren(parentType: SyncType, parentLocalId: Long) {
        types.filter { it.parent?.type === parentType }.forEach { child ->
            store.rows(child.name).values
                .filter { it.base != null && parentId(it.base, child.parent!!) == parentLocalId }
                .forEach { store.remove(child.name, it.localId) }
        }
    }

    companion object {
        const val CREATE = "CREATE"
        const val UPDATE = "UPDATE"
        const val DELETE = "DELETE"
    }
}

/**
 * How the next push plans [localId]'s working [state], once any hold on it is released ([row]: its sync row, if any).
 * Null when there is nothing to push (it is back at its base) or it waits for a held item that still has its key on
 * the server (Holds lists it); otherwise its change and, when the server would refuse that change, the row that holds
 * it instead. [PushPlanner] and [Holds.list] share it, so the held items listed are the ones the next push holds.
 */
internal fun planItem(
    type: SyncType,
    merger: Merger,
    localId: Long,
    row: SyncRow?,
    state: State,
    heldKeys: Map<String, Long>
): Pair<Change, SyncRow?>? {
    val base = row?.base
    if (base != null && merger.same(state, base)) return null
    if (type.naturalKey(state)?.let(heldKeys::get)?.let { it != localId } == true) return null
    val change = if (base == null) Change.Create(state) else Change.Update(row.remoteId!!, base, state)
    return change to type.validate(change)?.let { reason -> SyncRow(localId, row?.remoteId, base, reason, state) }
}

/**
 * Whether [state]'s parent has no remote id yet: its type defers the change ([SyncType.mutation] is null) until the
 * parent's CREATE lands, or for a never-linked parent, until a GET links it.
 */
internal fun waitsForParent(type: SyncType, state: State, refs: Refs): Boolean {
    val parent = type.parent ?: return false
    return parentId(state, parent)?.let { refs.remoteId(parent.type, it) } == null
}
