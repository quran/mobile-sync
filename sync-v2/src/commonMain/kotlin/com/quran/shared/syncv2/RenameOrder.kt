package com.quran.shared.syncv2

/**
 * Orders one type's UPDATEs that change a natural key, so the server's unique check never sees two items on one key
 * halfway through a POST. The rename that frees a key goes before the rename that takes it (a shift A→B, B→C pushes
 * B→C first). A cycle (a swap A↔B) can't be ordered: one of its items first moves to a temporary key from
 * [SyncType.disambiguate] ("B (2)") and takes its real key in the next phase, once the others moved.
 * [taken] holds every key of the type, working and base, so a temporary key collides with nothing.
 */
internal fun orderKeyChanges(type: SyncType, updates: List<Planned>, taken: Set<String>, refs: Refs): List<Planned> {
    fun baseKey(planned: Planned) = type.naturalKey((planned.change as Change.Update).base)
    fun newKey(planned: Planned) = type.naturalKey(planned.state)
    val (moving, staying) = updates.partition { baseKey(it) != null && newKey(it) != null && baseKey(it) != newKey(it) }
    if (moving.size < 2) return updates
    val pending = moving.toMutableList()
    val occupied = moving.mapNotNull(::baseKey).toMutableSet()
    val ordered = mutableListOf<Planned>()
    while (pending.isNotEmpty()) {
        val ready = pending.firstOrNull { newKey(it) !in occupied }
        if (ready != null) {
            ordered += ready
            pending -= ready
            occupied -= baseKey(ready)!!
            continue
        }
        // Every rename left waits on another: a cycle. Free one key through a temporary one.
        val first = pending.removeAt(0)
        occupied -= baseKey(first)!!
        ordered += temporary(type, first, taken, refs) ?: first
    }
    return staying + ordered
}

/** [planned] as an UPDATE to a free temporary key, or null when the type can't make one. */
private fun temporary(type: SyncType, planned: Planned, taken: Set<String>, refs: Refs): Planned? {
    val update = planned.change as Change.Update
    var n = 2
    while (n <= MAX_SUFFIX) {
        val candidate = type.disambiguate(planned.state, n) ?: return null
        if (type.naturalKey(candidate) !in taken) {
            val change = Change.Update(update.remoteId, update.base, candidate)
            return Planned(type, planned.localId, change, type.mutation(change, refs) ?: return null, holdState = planned.state)
        }
        n += 1
    }
    return null
}

private const val MAX_SUFFIX = 1000
