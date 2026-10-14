package com.quran.shared.syncv2

import co.touchlab.kermit.Logger

/**
 * Fetch side of a sync, run in one transaction with the new token: per type, in dependency order,
 * natural-key revival, the three-way merge, uniqueness repair and the write-back.
 */
class Reconciler(private val store: SyncStore, private val types: List<SyncType>, private val refs: Refs) {
    private val logger = Logger.withTag("SyncV2Reconciler")
    private val restoredParents = mutableMapOf<String, Set<Long>>()
    /** Children dropped because their parent was gone: a last resort that should never happen (see [dropOrphans]). */
    var droppedOrphans = 0
        private set

    fun reconcile(entries: List<RemoteEntry>) {
        restoredParents.clear()
        types.forEach { reconcileType(it, entries) }
        types.forEach { type -> type.prune(store.rows(type.name).values.mapNotNull { row -> row.base?.let { row.localId to it } }.toMap()) }
    }

    private fun reconcileType(type: SyncType, entries: List<RemoteEntry>) {
        val revived = revive(type)
        val remote = type.fromRemote(entries, refs).associateBy { it.remoteId }
        val working = type.readAll()
        val rows = store.rows(type.name)
        val items = buildItems(type, working, rows, remote)
        restoreCascadedChildren(type, items)
        releaseDuplicateHolds(type, items)
        val localDirty = dirtyParents(type)
        val remoteDirty = remotelyEditedParents(type, entries)
        items.forEach {
            it.localEdit = it.localId in localDirty || it.localId in revived
            it.remoteChildEdit = it.localId in remoteDirty
        }

        val merger = Merger(type)
        items.forEach(merger::merge)
        UniquenessRepair(type, merger, this::repointChildren).repair(items)
        dropOrphans(type, items)
        write(type, items)
    }

    private fun buildItems(
        type: SyncType,
        working: Map<Long, State>,
        rows: Map<Long, SyncRow>,
        remote: Map<String, RemoteItem>
    ): MutableList<MergeItem> {
        val items = mutableListOf<MergeItem>()
        rows.values.forEach { row ->
            val state = working[row.localId]
            if (row.base == null && row.remoteId == null && state == null) {
                store.remove(type.name, row.localId)
                return@forEach
            }
            items += MergeItem(
                localId = row.localId,
                remoteId = row.remoteId,
                base = row.base,
                working = state,
                remote = row.remoteId?.let(remote::get),
                original = state,
                heldReason = row.heldReason,
                heldState = row.heldState,
                heldUntil = row.heldUntil
            )
        }
        working.forEach { (localId, state) ->
            if (localId !in rows) items += MergeItem(localId, null, null, state, null, state)
        }
        val bound = rows.values.mapNotNull { it.remoteId }.toSet()
        remote.values.filter { it.remoteId !in bound }.forEach {
            items += MergeItem(null, it.remoteId, null, null, it, null)
        }
        return items
    }

    /**
     * Natural-key revival (S5): a new local row whose key matches a local tombstone takes over that
     * tombstone's remote id and base, so "delete then re-add" becomes one UPDATE (or nothing).
     */
    private fun revive(type: SyncType): Set<Long> {
        val working = type.readAll()
        val rows = store.rows(type.name)
        val tombstones = rows.values.filter { it.base != null && it.localId !in working }
            .mapNotNull { row -> type.naturalKey(row.base!!)?.let { it to row } }.toMap()
            .toMutableMap()
        if (tombstones.isEmpty()) return emptySet()
        val revived = mutableSetOf<Long>()
        working.forEach { (localId, state) ->
            if (rows[localId]?.base != null) return@forEach
            val tombstone = type.naturalKey(state)?.let(tombstones::remove) ?: return@forEach
            store.remove(type.name, tombstone.localId)
            store.put(type.name, SyncRow(localId, tombstone.remoteId, tombstone.base))
            repointChildBases(type, tombstone.localId, localId)
            // Deleted and added again: an edit, even when the state equals base, so a remote delete doesn't win.
            revived += localId
        }
        return revived
    }

    /** A parent this sync restored (edit beats delete) gets back the children its local delete cascaded. */
    private fun restoreCascadedChildren(type: SyncType, items: List<MergeItem>) {
        val parent = type.parent ?: return
        val restored = restoredParents[parent.type.name].orEmpty()
        if (restored.isEmpty()) return
        items.filter { it.working == null && it.base != null && it.remote !is RemoteItem.Deleted }
            .filter { parentId(it.base!!, parent) in restored }
            .forEach { it.working = it.base }
    }

    /**
     * Parents with a child another device added or changed. For a parent deleted locally, that is "deleted locally,
     * edited remotely": the edit wins, so the parent is restored as the server has it, with all its children.
     */
    private fun remotelyEditedParents(type: SyncType, entries: List<RemoteEntry>): Set<Long> =
        children(type).flatMapTo(mutableSetOf()) { child ->
            val merger = Merger(child)
            child.fromRemote(entries, refs).filterIsInstance<RemoteItem.Upsert>()
                .filter { !merger.same(it.state, store.rowByRemote(child.name, it.remoteId)?.base) }
                .mapNotNull { parentId(it.state, child.parent!!) }
        }

    /** A remote change to the type may have freed a name another device held: retry items held for a duplicate. */
    private fun releaseDuplicateHolds(type: SyncType, items: List<MergeItem>) {
        val merger = Merger(type)
        val remoteChanged = items.any { item ->
            when (val remote = item.remote) {
                is RemoteItem.Deleted -> item.base != null
                is RemoteItem.Upsert -> !merger.same(remote.state, item.base)
                null -> false
            }
        }
        if (!remoteChanged) return
        items.filter { it.heldReason == SyncErrors.UNIQUE_HOLD }.forEach {
            logger.i { "${type.name} ${it.localId}: a remote change may have freed its name; retrying" }
            it.heldReason = null
            it.heldState = null
        }
    }

    /** A child never outlives its parent locally; the parent's DELETE covers it on the server. */
    private fun dropOrphans(type: SyncType, items: List<MergeItem>) {
        val parent = type.parent ?: return
        val parents = parent.type.readAll().keys
        items.forEach { item ->
            val parentId = item.working?.let { parentId(it, parent) } ?: return@forEach
            if (parentId in parents) return@forEach
            logger.w { "${type.name}: dropping ${item.remoteId ?: item.localId}, its parent $parentId is gone" }
            item.working = null
            droppedOrphans += 1
        }
    }

    /** Parents with a local child change: a child edit counts as an edit of the parent (edit beats delete). */
    private fun dirtyParents(type: SyncType): Set<Long> = children(type).flatMapTo(mutableSetOf()) { child ->
        val rows = store.rows(child.name)
        val merger = Merger(child)
        child.readAll().filter { (localId, state) -> !merger.same(state, rows[localId]?.base) }
            .values.mapNotNull { parentId(it, child.parent!!) }
    }

    private fun write(type: SyncType, items: List<MergeItem>) {
        val restored = mutableSetOf<Long>()
        items.forEach { item ->
            if (item.localId != null && item.original != null && item.working == null) type.delete(item.localId!!)
        }
        items.forEach { item ->
            val original = item.original ?: return@forEach
            val working = item.working ?: return@forEach
            if (type.naturalKey(original) != type.naturalKey(working)) type.park(item.localId!!)
        }
        items.forEach { item ->
            val working = item.working ?: return@forEach
            when {
                item.localId == null -> item.localId = type.insert(working)
                item.original == null -> {
                    type.insert(working, item.localId)
                    restored += item.localId!!
                }
                item.original != working -> type.update(item.localId!!, working)
            }
        }
        items.forEach { item ->
            val localId = item.localId
                ?: store.newTombstoneId(type.name, type.readAll().keys).takeIf { item.bindAsTombstone }
                ?: return@forEach
            // A hold lasts while there is something to push: a working row, or a tombstone (a held DELETE).
            val held = item.heldReason?.takeIf { item.working != null || item.base != null }
            val row = SyncRow(localId, item.remoteId, item.base, held, item.heldState, item.heldUntil)
            store.put(type.name, if (held == null) row.unheld() else row)
        }
        restoredParents[type.name] = restored
        logger.d { "${type.name}: merged ${items.size} items, restored ${restored.size}" }
    }

    /** Moves local children of [from] to [to] (linking a local-only parent into a remote one). */
    private fun repointChildren(type: SyncType, from: Long, to: Long) {
        children(type).forEach { child ->
            val field = child.parent!!
            val all = child.readAll()
            val keys = all.values.mapNotNull(child::naturalKey).toMutableSet()
            all.filter { parentId(it.value, field) == from }.forEach { (localId, state) ->
                val moved = state.with(field.field(to))
                val key = child.naturalKey(moved)
                if (key != null && !keys.add(key)) child.delete(localId) else child.update(localId, moved)
            }
        }
    }

    private fun repointChildBases(type: SyncType, from: Long, to: Long) {
        children(type).forEach { child ->
            val field = child.parent!!
            store.rows(child.name).values.filter { it.base != null && parentId(it.base, field) == from }.forEach {
                store.put(child.name, it.copy(base = it.base!!.with(field.field(to))))
            }
        }
    }

    private fun children(type: SyncType) = types.filter { it.parent?.type === type }
}

internal fun parentId(state: State, ref: ParentRef): Long? = ref.field.read(state)
