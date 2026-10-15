package com.quran.shared.syncv2

import co.touchlab.kermit.Logger
import kotlin.math.abs

/**
 * Fetch side of a sync, run in one transaction with the new token: per type, in dependency order,
 * natural-key revival, the three-way merge, uniqueness repair and the write-back.
 */
class Reconciler(private val store: SyncStore, private val types: List<SyncType>, private val refs: Refs) {
    private val logger = Logger.withTag("SyncV2Reconciler")
    private val restoredParents = mutableMapOf<String, Set<Long>>()
    /** Parents edited themselves (not only through a child) and deleted remotely: recreated with all their children. */
    private val recreatedParents = mutableMapOf<String, Set<Long>>()
    /** Children dropped because their parent was gone: a last resort that should never happen (see [dropOrphans]). */
    var droppedOrphans = 0
        private set

    fun reconcile(entries: List<RemoteEntry>) {
        restoredParents.clear()
        recreatedParents.clear()
        types.forEach { reconcileType(it, entries) }
        types.forEach { type -> type.prune(store.rows(type.name).values.mapNotNull { row -> row.base?.let { row.localId to it } }.toMap()) }
    }

    private fun reconcileType(type: SyncType, entries: List<RemoteEntry>) {
        val revived = revive(type)
        val remote = remoteItems(type, entries)
        val working = type.readAll()
        val rows = store.rows(type.name)
        val items = buildItems(type, working, rows, remote)
        restoreCascadedChildren(type, items)
        releaseDuplicateHolds(type, items)
        val localDirty = dirtyParents(type)
        val remoteDirty = remotelyEditedParents(type, entries)
        val recreatedParent = recreatedParentOf(type)
        items.forEach {
            it.localEdit = it.localId in localDirty || it.localId in revived || recreatedParent(it)
            it.remoteChildEdit = it.localId in remoteDirty
        }

        val merger = Merger(type)
        val ownEdits = items.filter { it.working != null && (it.localId in revived || !merger.same(it.working, it.base)) }
            .mapNotNull { it.localId }.toSet()
        items.forEach(merger::merge)
        recreatedParents[type.name] = items
            .filter { it.remote is RemoteItem.Deleted && it.working != null && it.localId in ownEdits }
            .mapNotNull { it.localId }.toSet()
        linkEqualNewItems(type, items)
        UniquenessRepair(type, merger, this::repointChildren).repair(items)
        dropOrphans(type, items)
        write(type, items)
    }

    /**
     * [type]'s items in the GET. Its parents are written by now, so an item still [RemoteItem.Unresolved] is skipped
     * and reported here (earlier mappings of the same GET may not resolve it yet, and stay quiet).
     */
    private fun remoteItems(type: SyncType, entries: List<RemoteEntry>): Map<String, RemoteItem> {
        val (unresolved, items) = type.fromRemote(entries, refs).partition { it is RemoteItem.Unresolved }
        unresolved.forEach { logger.w { "${type.name}: skipping ${it.remoteId}: ${(it as RemoteItem.Unresolved).reason}" } }
        return items.associateBy { it.remoteId }
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
     * Natural-key revival: a new local row whose key matches a local tombstone takes over that
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

    /**
     * Edit beats delete for a parent edited itself (a renamed collection deleted remotely): it is recreated as this
     * device has it, children included, so their remote deletes (the server's cascade) are edits beaten too. A parent
     * recreated only because a child was added or changed keeps just those children.
     */
    private fun recreatedParentOf(type: SyncType): (MergeItem) -> Boolean {
        val parent = type.parent ?: return { false }
        val recreated = recreatedParents[parent.type.name].orEmpty()
        if (recreated.isEmpty()) return { false }
        return { item -> item.working?.let { parentId(it, parent) } in recreated }
    }

    /**
     * Links each item with no remote counterpart to an unbound new remote item with the same [SyncType.linkKey]
     * identity instead of pushing it; the remote version wins. One to one, closest [LinkKey.time] first, then local and
     * remote id, so the choice is deterministic.
     */
    private fun linkEqualNewItems(type: SyncType, items: List<MergeItem>) {
        val newRemote = items.filter { it.localId == null && it.remoteId != null && it.working != null }
            .mapNotNull { item -> type.linkKey(item.working!!)?.let { item to it } }
            .groupBy { it.second.identity }
        if (newRemote.isEmpty()) return
        val pairs = items.filter { it.localId != null && it.remoteId == null && it.base == null && it.working != null }
            .flatMap { local ->
                val key = type.linkKey(local.working!!) ?: return@flatMap emptyList()
                newRemote[key.identity].orEmpty().map { (remote, remoteKey) -> LinkPair(local, remote, abs(key.time - remoteKey.time)) }
            }
            .sortedWith(compareBy({ it.distance }, { it.local.localId }, { it.remote.remoteId }))
        val linked = mutableSetOf<MergeItem>()
        pairs.forEach { (local, target) ->
            if (local in linked || target in linked) return@forEach
            logger.i { "${type.name}: linking new ${local.localId} to the equal remote ${target.remoteId}" }
            linked += local
            linked += target
            target.localId = local.localId
            target.original = local.original
            target.heldReason = null
            local.localId = null
            local.working = null
            local.original = null
        }
    }

    private data class LinkPair(val local: MergeItem, val remote: MergeItem, val distance: Long)

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
                is RemoteItem.Unresolved, null -> false
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
