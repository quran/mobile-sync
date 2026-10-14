package com.quran.shared.syncv2

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One item during the merge. [working] and [base] start as stored and end as what gets written.
 * [localId] is null for a remote item this device has not seen yet.
 * [localEdit]: changed locally beyond `working != base` (a child changed locally, or revived after a delete).
 * [remoteChildEdit]: another device added or changed one of its children.
 */
class MergeItem(
    var localId: Long?,
    var remoteId: String?,
    var base: State?,
    var working: State?,
    val remote: RemoteItem?,
    var original: State?,
    var heldReason: String? = null,
    var heldState: State? = null,
    val heldUntil: Long? = null,
    var localEdit: Boolean = false,
    var remoteChildEdit: Boolean = false,
    /** A remote item that must be deleted on the server though it never had a local row: stored as a tombstone. */
    var bindAsTombstone: Boolean = false
)

/** The generic three-way merge. "Remote changed" means its data differs from base, not that it was in the GET. */
class Merger(private val type: SyncType) {

    fun same(a: State?, b: State?): Boolean =
        if (a == null || b == null) a == b else strip(a) == strip(b)

    private fun strip(state: State) = JsonObject(state.filterKeys { it !in type.ignoredFields })

    /** Merges one item. Edit beats delete both ways, and a child's change counts as an edit of its parent. */
    fun merge(item: MergeItem) {
        val remote = item.remote
        val base = item.base
        val working = item.working
        if (remote == null) {
            // Deleted locally while another device added a child: the edit wins, so the delete is cancelled.
            if (working == null && base != null && item.remoteChildEdit) item.working = base
            return
        }
        val localChanged = when {
            base == null || working == null -> true
            else -> !same(working, base) || item.localEdit
        }
        when (remote) {
            is RemoteItem.Deleted -> when {
                working == null -> forget(item)
                // Edit beats delete: recreate it, so it is pushed as a CREATE.
                localChanged -> forget(item)
                else -> {
                    item.working = null
                    forget(item)
                }
            }

            is RemoteItem.Upsert -> {
                val remoteState = remote.state
                val remoteChanged = !same(remoteState, base)
                when {
                    base == null && working == null -> {
                        item.working = remoteState
                        item.base = remoteState
                    }

                    // Deleted locally, edited remotely (or one of its children was): the edit wins.
                    working == null -> if (remoteChanged || item.remoteChildEdit) {
                        item.working = remoteState
                        item.base = remoteState
                    }

                    // Unchanged here: take the server's state, even when it differs only in ignored fields (tie-breakers
                    // such as a membership's addedAt), so every device ranks by the same values.
                    !localChanged -> {
                        item.working = remoteState
                        item.base = remoteState
                    }

                    !remoteChanged -> Unit

                    else -> {
                        item.working = fieldMerge(base, working, remoteState)
                        item.base = remoteState
                    }
                }
            }
        }
    }

    /**
     * Field by field: a side that changed a field alone wins it; if both changed it, the newer clock wins.
     * With no [base], every differing field is "changed on both sides". [SyncType.mergeWholeObject]: newer wins.
     */
    fun fieldMerge(base: State?, local: State, remote: State): State {
        val localNewer = type.clock(local) > type.clock(remote)
        if (type.mergeWholeObject) return if (localNewer) local else remote
        val keys = local.keys + remote.keys
        return JsonObject(keys.associateWith { key ->
            val l: JsonElement? = local[key]
            val r: JsonElement? = remote[key]
            val b: JsonElement? = base?.get(key)
            when {
                key in type.ignoredFields -> if (localNewer) l ?: r else r ?: l
                l == r -> l
                base != null && l == b -> r
                base != null && r == b -> l
                localNewer -> l
                else -> r
            } ?: r ?: l!!
        })
    }

    private fun forget(item: MergeItem) {
        item.remoteId = null
        item.base = null
    }
}
