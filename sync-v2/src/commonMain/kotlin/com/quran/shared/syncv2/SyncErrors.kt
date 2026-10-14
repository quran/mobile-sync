package com.quran.shared.syncv2

import com.quran.shared.syncv2.PushPlanner.Companion.CREATE

/**
 * How a failed POST is handled, by `error.code` first and HTTP status second. Only an allowlist of item errors ever
 * holds an item; everything else aborts the run, keeps local changes, and retries later. "The item" is the one at
 * `mutationIndex`, or the batch's only item; without either, the batch is split (see [Pusher]).
 * "Landed" means something landed in this sync: the server is up and the request's scope allowed.
 *
 * | Error | Action |
 * |---|---|
 * | 409 `OutOfSyncError` | fetch, merge, push again |
 * | no backend `error.code` | abort, retryable: a gateway, proxy or maintenance page says nothing about any item |
 * | 401, 408, 429, 5xx other than 500 | abort, retryable; never hold |
 * | 409 `UniqueViolationError` | fetch and repair once; then hold the item until a GET brings a remote change to |
 * |     | its type, or for a day |
 * | 400, 422 | hold the item |
 * | 404 on UPDATE or DELETE | the item is gone remotely: forget its base (edit beats delete) |
 * | 403 with an index | revert the item to base |
 * | 403 without an index | after something landed, the item's: revert it. Before, split the batch to find something |
 * |     | that lands; a lone 403 stops (retryable, 6 h backoff), as it may be a scope error |
 * | 500 | fetch and retry; after a second one, fetch (to resolve its outbox), then: after something landed, hold the |
 * |     | item; before, post the rest without it (a 5xx there too is an outage: abort, hold nothing). A lone item is |
 * |     | held only when it failed like this in an earlier run |
 * | any other backend error (a 404 on CREATE, an unknown status) | abort, retryable; held when the same item fails |
 * |     | with the same status in a later run |
 *
 * Holds for repeated failures expire after [Pusher.HOLD_MILLIS] (1 h, 6 h, 24 h by the runs the item failed in).
 */
internal object SyncErrors {
    const val OUT_OF_SYNC = "OutOfSyncError"
    const val UNIQUE_VIOLATION = "UniqueViolationError"

    enum class Action { RESTART, UNIQUE_VIOLATION, ITEM, SERVER_ERROR, FORBIDDEN, DETERMINISTIC, ABORT_RETRYABLE }

    /** [culpritKind] is the failing item's kind when it is known (an index, or a batch of one). */
    fun classify(error: SyncHttpException, culpritKind: String?): Action = when {
        error.code == OUT_OF_SYNC -> Action.RESTART
        error.code == UNIQUE_VIOLATION -> Action.UNIQUE_VIOLATION
        // Only the backend names an error; a codeless 404 or 422 is a routing or maintenance page, not an item.
        error.code == null -> Action.ABORT_RETRYABLE
        error.status == 400 || error.status == 422 -> Action.ITEM
        error.status == 404 && culpritKind != CREATE -> Action.ITEM
        error.status == 403 -> if (error.mutationIndex != null) Action.ITEM else Action.FORBIDDEN
        error.status == 500 -> Action.SERVER_ERROR
        isRetryable(error.status) -> Action.ABORT_RETRYABLE
        else -> Action.DETERMINISTIC
    }

    fun holdReason(error: SyncHttpException) = "${error.status} ${error.code ?: ""}".trim()

    val UNIQUE_HOLD = "409 $UNIQUE_VIOLATION"

    /** Whether a failure of the whole run (a GET, or an aborted POST) is worth retrying with backoff. */
    fun isRetryable(status: Int): Boolean = status == 401 || status == 408 || status == 429 || status >= 500
}
