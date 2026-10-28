package com.quran.shared.pipeline

/**
 * The items whose local state the server doesn't have yet, as [QuranDataService.unsyncedItems] counts them. Both
 * counts come from one read, and each item is in exactly one of them, so `pending + held` is the total that logout
 * would wipe.
 *
 * @param pending the items the next sync sends: new, edited or deleted since they last synced, and not held. A verse
 *   in a new collection counts here, though it waits for the collection's server id.
 * @param held the items that don't sync until they are fixed: exactly [QuranDataService.heldItems], so the held items
 *   and the items waiting on them ([HeldReason.WAITING_ON_PARENT], [HeldReason.WAITING_ON_OTHER_ITEM]), which only
 *   sync once the item they wait on does.
 */
data class UnsyncedItemCounts(val pending: Int, val held: Int)
