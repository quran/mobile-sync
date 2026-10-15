package com.quran.shared.syncv2

import co.touchlab.kermit.Logger
import com.quran.shared.syncv2.PushPlanner.Companion.CREATE
import com.quran.shared.syncv2.PushPlanner.Companion.DELETE
import com.quran.shared.syncv2.PushPlanner.Companion.UPDATE

/**
 * Resolves a POST whose response was lost. POST is atomic, so the GET from the outbox head either contains
 * the whole batch or none of it: one item found with the content it was pushed with proves the batch landed.
 * Identity: remote id for UPDATE/DELETE. For CREATE: [SyncType.outboxKey] alone when the type has one (a remote id
 * another local row already holds never matches), otherwise the natural key together with the pushed content. An
 * outbox known to have [Outbox.landed] needs no proof; its unmatched CREATEs stay unsynced and are pushed again (the
 * server upserts natural keys; notes match by outboxKey).
 * Window: if another device changed or deleted such an item before this GET, it no longer matches, and the push
 * again overwrites that change or brings the item back (see [Pusher]).
 */
class OutboxResolver(
    private val store: SyncStore,
    private val types: List<SyncType>,
    private val refs: Refs,
    private val planner: PushPlanner
) {
    private val logger = Logger.withTag("SyncV2Outbox")

    fun resolve(entries: List<RemoteEntry>, account: String) {
        val outboxes = store.outboxes
        store.outboxes = emptyList()
        outboxes.filter { it.account == account }.forEach { resolve(it, entries) }
    }

    private fun resolve(outbox: Outbox, entries: List<RemoteEntry>) {
        val typesByName = types.associateBy { it.name }
        val remoteByType = types.associateWith { type -> type.fromRemote(entries, refs).filter { it.timestamp > outbox.head } }
        // A remote item answers one CREATE at most: two pushes with one identity must not both bind to it.
        val taken = mutableSetOf<Pair<String, String>>()
        val matches = outbox.items.associateWith { item ->
            val type = typesByName[item.type] ?: return@associateWith null
            match(type, item, remoteByType.getValue(type).filter { item.type to it.remoteId !in taken })
                ?.also { if (item.kind == CREATE) taken += item.type to it.remoteId }
        }
        val landed = outbox.landed || matches.values.any { it != null }
        logger.i { "Outbox of ${outbox.items.size} at head ${outbox.head}: landed=$landed" }
        if (!landed) return
        matches.forEach { (item, match) ->
            val type = typesByName[item.type] ?: return@forEach
            when (item.kind) {
                CREATE -> match?.let { store.put(type.name, SyncRow(item.localId, it.remoteId, item.state)) }
                UPDATE -> store.put(type.name, SyncRow(item.localId, item.remoteId, item.state))
                DELETE -> {
                    store.remove(type.name, item.localId)
                    planner.forgetCoveredChildren(type, item.localId)
                }
            }
        }
    }

    private fun match(type: SyncType, item: OutboxItem, remote: List<RemoteItem>): RemoteItem? {
        val merger = Merger(type)
        return when (item.kind) {
            CREATE -> {
                val state = item.state ?: return null
                val outboxKey = type.outboxKey(state)
                val naturalKey = type.naturalKey(state)
                remote.filterIsInstance<RemoteItem.Upsert>().firstOrNull {
                    when {
                        // A client identity of this create: it matches even if another device edited it since.
                        outboxKey != null ->
                            type.outboxKey(it.state) == outboxKey && !boundToAnother(type, it.remoteId, item.localId)
                        // A natural key is anyone's: only the content proves it was this push.
                        naturalKey != null -> type.naturalKey(it.state) == naturalKey && merger.same(it.state, state)
                        else -> false
                    }
                }
            }
            UPDATE -> remote.filterIsInstance<RemoteItem.Upsert>().firstOrNull {
                it.remoteId == item.remoteId && merger.same(it.state, item.state)
            }
            DELETE -> remote.filterIsInstance<RemoteItem.Deleted>().firstOrNull { it.remoteId == item.remoteId }
            else -> null
        }
    }

    private fun boundToAnother(type: SyncType, remoteId: String, localId: Long): Boolean =
        refs.localId(type, remoteId).let { it != null && it != localId }
}
