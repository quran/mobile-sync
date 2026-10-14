package com.quran.shared.syncv2

import co.touchlab.kermit.Logger

/**
 * Restores the local unique indexes after the merge, before anything is written:
 * - a local-only row clashing with a remote-backed one is linked to it;
 * - a local key change (rename, move) clashing with a remote-backed row is reverted to base, except for
 *   [SyncType.mergeWholeObject] types, where the newer of the two keeps the key and the older one is deleted;
 * - anything else is disambiguated ("Study (2)") or, for types that can't, dropped.
 * Each pass resolves every clash it sees; a revert can uncover a new clash (a chain of local renames). If the passes
 * run out, every remaining clash is disambiguated, which always converges.
 */
class UniquenessRepair(
    private val type: SyncType,
    private val merger: Merger,
    private val repointChildren: (SyncType, Long, Long) -> Unit
) {
    private val logger = Logger.withTag("SyncV2UniquenessRepair")

    fun repair(items: List<MergeItem>) {
        repeat(MAX_PASSES) {
            val clashes = clashes(items)
            if (clashes.isEmpty()) return
            clashes.forEach { resolve(it, items) }
        }
        val remaining = clashes(items)
        if (remaining.isNotEmpty()) logger.w { "${type.name}: disambiguating ${remaining.size} clashes left after $MAX_PASSES passes" }
        remaining.forEach { disambiguate(it, items) }
    }

    private fun clashes(items: List<MergeItem>): List<List<MergeItem>> = items.filter { it.working != null }
        .groupBy { type.naturalKey(it.working!!) }
        .filterKeys { it != null }
        .values.filter { it.size > 1 }

    private fun resolve(group: List<MergeItem>, items: List<MergeItem>) {
        val key = type.naturalKey(group.first().working!!)
        val remoteBacked = group.filter { it.remoteId != null }
        val localOnly = group.filter { it.remoteId == null }
        if (remoteBacked.size == 1) {
            localOnly.forEach { link(it, remoteBacked.single()) }
            return
        }
        val localKeyChanges = remoteBacked.filter { it.base != null && type.naturalKey(it.base!!) != key }
        if (localKeyChanges.isNotEmpty() && type.mergeWholeObject) {
            // Whole-object types (sessions): the newer item keeps the key and the older one goes, as the server's
            // session UPDATE deletes the session already at its target. Every loser becomes a tombstone, so its own
            // DELETE is pushed: a remote loser is never inserted, but is bound to a tombstone, so it can't stay on the
            // server if the winner's UPDATE never lands or moves on.
            group.sortedByDescending { type.clock(it.working!!) }.drop(1).forEach {
                logger.i { "${type.name}: ${it.remoteId} loses $key to a newer item" }
                it.working = null
                if (it.localId == null) it.bindAsTombstone = true
            }
            return
        }
        if (localKeyChanges.isNotEmpty()) {
            localKeyChanges.forEach {
                logger.i { "${type.name}: reverting local key change of ${it.remoteId} that clashes on $key" }
                it.working = it.base
            }
            return
        }
        disambiguate(group, items)
    }

    /** No local cause: keep the newest row and make the others unique (or drop them, for types that can't). */
    private fun disambiguate(group: List<MergeItem>, items: List<MergeItem>) {
        val taken = items.mapNotNull { it.working?.let(type::naturalKey) }.toMutableSet()
        group.sortedByDescending { type.clock(it.working!!) }.drop(1).forEach { item ->
            var n = 2
            var candidate = type.disambiguate(item.working!!, n)
            while (candidate != null && type.naturalKey(candidate)?.let(taken::add) == false) {
                n += 1
                candidate = if (n > MAX_SUFFIX) null else type.disambiguate(item.working!!, n)
            }
            logger.i { "${type.name}: disambiguating a clash -> ${candidate?.let(type::naturalKey)}" }
            item.working = candidate
        }
    }

    /** Merges a local-only row into the remote-backed row with the same key, as "both changed". */
    private fun link(local: MergeItem, target: MergeItem) {
        target.working = merger.fieldMerge(null, local.working!!, target.working!!)
        val localId = local.localId
        local.working = null
        local.heldReason = null
        if (localId == null) return
        if (target.localId == null) {
            target.localId = localId
            target.original = local.original
            local.localId = null
        } else {
            repointChildren(type, localId, target.localId!!)
        }
    }

    private companion object {
        const val MAX_PASSES = 8
        const val MAX_SUFFIX = 1000
    }
}
