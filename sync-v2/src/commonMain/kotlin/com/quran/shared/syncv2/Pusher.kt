package com.quran.shared.syncv2

import co.touchlab.kermit.Logger
import com.quran.shared.syncv2.PushPlanner.Companion.CREATE
import com.quran.shared.syncv2.PushPlanner.Companion.DELETE
import com.quran.shared.syncv2.PushPlanner.Companion.UPDATE

sealed interface PushOutcome {
    data object Done : PushOutcome
    /** Fetch and merge again, then push (409 OutOfSync, 409 UniqueViolation, a 500). */
    data object Restart : PushOutcome
    /** Every phase made progress, but local changes kept coming (edits during each POST): run another round. */
    data object MorePending : PushOutcome
    data class Failed(val reason: String, val retryable: Boolean, val retryAfterMillis: Long? = null) : PushOutcome
}

/** The 500s one batch got in this sync, and the last one (its index points at the item). */
data class ServerErrors(val count: Int, val last: SyncHttpException)

/** What one sync has learned so far, across its rounds. */
class PushMemory {
    /** 500s by batch signature: the first is retried after a GET, after the second the batch is isolated. */
    val serverErrors = mutableMapOf<String, ServerErrors>()
    var uniqueRestarts = 0
    /**
     * Something landed in this sync: the server is up and the request's scope is allowed. Until then a 500 may be
     * an outage and a 403 without an index a scope error; after it, both are the item's.
     */
    var landed = false
    /** Items pushed (settled) in this sync. */
    var pushed = 0
    /** Items settled, held, reverted or forgotten in this sync. */
    var progress = 0
    /** Children the merge dropped because their parent was gone. */
    var droppedOrphans = 0
}

/**
 * Pushes the planned changes in dependency phases. Each phase's POST uses the previous POST's head, but the
 * stored token stays at the GET head, so the next GET brings back this device's pushes and their side effects.
 *
 * A rejected POST is classified by [SyncErrors]. A known item (an index, or a batch of one) is handled directly;
 * otherwise, or while the server isn't proven up ([PushMemory.landed]), the batch is [split]: one part is posted on
 * its own and the rest planned again. That one mechanism is both the bisect (no index) and the control POST (an
 * ambiguous 500 at a known index).
 */
class Pusher(
    private val generation: Generation,
    private val store: SyncStore,
    private val api: SyncApi,
    private val planner: PushPlanner,
    private val account: String,
    private val memory: PushMemory,
    private val now: () -> Long
) {
    private val logger = Logger.withTag("SyncV2Pusher")

    private sealed interface Sent {
        data class Ok(val head: Long) : Sent
        /** Some items landed, or were held, reverted or forgotten: plan again. */
        data class Changed(val head: Long) : Sent
        data class Abort(val outcome: PushOutcome) : Sent
    }

    /**
     * One POST: landed, or rejected as a whole (POST is atomic). A landed POST is [Ok.settled] unless its answers
     * couldn't be told apart (see [post]); then a GET must settle it first.
     */
    private sealed interface Posted {
        data class Ok(val head: Long, val settled: Boolean = true) : Posted
        data class Rejected(val error: SyncHttpException) : Posted
    }

    suspend fun push(startHead: Long): PushOutcome {
        var head = startHead
        repeat(MAX_PHASES) {
            val plan = generation.transaction { planner.plan() }
            if (plan.isEmpty()) return PushOutcome.Done
            val progress = memory.progress
            for (batch in plan.chunked(MAX_MUTATIONS_PER_POST)) {
                // A batch that failed with 500 twice is not sent whole again: a GET resolved its outbox, now isolate.
                val suspect = memory.serverErrors[signature(batch)]?.takeIf { it.count >= 2 }
                val sent = if (suspect != null) serverError(suspect.last, batch, head) else send(batch, head)
                when (sent) {
                    is Sent.Ok -> head = sent.head
                    is Sent.Changed -> {
                        head = sent.head
                        break
                    }
                    is Sent.Abort -> return sent.outcome
                }
            }
            // A guard against a bug, not a retry policy: every phase settles, holds, reverts or forgets something.
            if (memory.progress == progress) return PushOutcome.Failed("no progress pushing ${plan.size} changes", retryable = false)
        }
        return PushOutcome.MorePending
    }

    private suspend fun send(batch: List<Planned>, head: Long): Sent = when (val posted = post(batch, head)) {
        is Posted.Ok -> landed(posted, Sent.Ok(posted.head))
        is Posted.Rejected -> rejected(posted.error, batch, head)
    }

    /** [next], unless the POST landed unsettled: nothing more is planned until a GET settles it from its outbox. */
    private fun landed(posted: Posted.Ok, next: Sent): Sent = if (posted.settled) next else Sent.Abort(PushOutcome.Restart)

    private suspend fun post(batch: List<Planned>, head: Long): Posted {
        // Added next to any batch a 5xx left unresolved, never over it: that one may have landed.
        val outbox = Outbox(account, head, batch.map { OutboxItem(it.type.name, it.localId, it.kind, it.state, it.remoteId) })
        generation.transaction { store.outboxes = store.outboxes + outbox }
        val result = try {
            api.post(head, batch.map { it.mutation })
        } catch (error: SyncHttpException) {
            logger.w { "POST of ${batch.size} failed: ${error.message} (index=${error.mutationIndex})" }
            // A 4xx was rejected before anything applied; a 5xx may have landed, so its outbox stays for the next GET.
            if (error.status < 500) generation.transaction { store.outboxes = store.outboxes - outbox }
            return Posted.Rejected(error)
        }
        // The server may answer fewer mutations than it was sent (it collapses some creates that share a natural key).
        // Then a position no longer says which answer is whose: the batch landed, and the next GET settles it from
        // its outbox, by identity. Never settle by position alone.
        // A known window: a CREATE whose item another device changes or deletes between this POST and that GET no
        // longer matches by content, so it stays unsynced and is pushed again. The server upserts it, which overwrites
        // that device's change or brings back what it deleted. It needs a shortened response and a concurrent write
        // to the same item within one round trip; types with an outboxKey (notes) are not affected.
        val settled = result.mutations.size == batch.size
        if (!settled) logger.w { "POST of ${batch.size} answered ${result.mutations.size}; settling it from the next GET" }
        generation.transaction {
            // Landing at this head proves no earlier POST at this head landed (it would have moved the head).
            store.outboxes = store.outboxes.filter { it.head != head }
            if (settled) {
                batch.forEachIndexed { index, planned -> settle(planned, result.mutations[index]) }
            } else {
                store.outboxes = store.outboxes + outbox.copy(landed = true)
            }
            val landed = batch.map(::failureKey).toSet()
            if (store.failures.keys.any { it in landed }) store.failures = store.failures - landed
        }
        memory.landed = true
        memory.pushed += batch.size
        memory.progress += batch.size
        return Posted.Ok(result.head, settled)
    }

    private suspend fun rejected(error: SyncHttpException, batch: List<Planned>, head: Long): Sent {
        val culprit = error.mutationIndex?.let(batch::getOrNull) ?: batch.singleOrNull()
        return when (SyncErrors.classify(error, culprit?.kind)) {
            SyncErrors.Action.RESTART -> Sent.Abort(PushOutcome.Restart)
            SyncErrors.Action.ABORT_RETRYABLE -> Sent.Abort(PushOutcome.Failed(failure(error), retryable = true))
            SyncErrors.Action.ITEM -> item(error, batch, culprit, head)
            SyncErrors.Action.UNIQUE_VIOLATION ->
                if (memory.uniqueRestarts++ == 0) Sent.Abort(PushOutcome.Restart) else item(error, batch, culprit, head)
            // A scope error for the whole request, or an item's 403 from a server without the index.
            SyncErrors.Action.FORBIDDEN -> when {
                memory.landed -> item(error, batch, culprit, head)
                batch.size > 1 -> bisect(error, batch, head)
                else -> forbidden(error)
            }
            // A backend error off the allowlist: the run aborts, and an item failing like it in a later run is held.
            SyncErrors.Action.DETERMINISTIC ->
                if (culprit == null) bisect(error, batch, head) else repeated(culprit, error, head)
            SyncErrors.Action.SERVER_ERROR -> {
                val signature = signature(batch)
                memory.serverErrors[signature] = ServerErrors((memory.serverErrors[signature]?.count ?: 0) + 1, error)
                when {
                    memory.landed -> serverError(error, batch, head)
                    // May be transient (or may have landed: its outbox is kept): fetch and try once more. After a
                    // second one, a GET resolves its outbox before anything else, then the next round isolates it.
                    else -> Sent.Abort(PushOutcome.Restart)
                }
            }
        }
    }

    /** A 500 that repeated, or one after something landed: find the item, or tell an outage from it. */
    private suspend fun serverError(error: SyncHttpException, batch: List<Planned>, head: Long): Sent {
        val culprit = error.mutationIndex?.let(batch::getOrNull) ?: batch.singleOrNull()
        return when {
            culprit == null -> bisect(error, batch, head)
            memory.landed -> hold(culprit, error, head)
            batch.size == 1 -> repeated(culprit, error, head)
            // A control: the rest without the culprit.
            else -> split(error, batch - culprit, listOf(culprit), head)
        }
    }

    /** No item to blame: split the batch in halves (the only place that bisects; not needed with BE1's index). */
    private suspend fun bisect(error: SyncHttpException, batch: List<Planned>, head: Long): Sent {
        val half = (batch.size + 1) / 2
        return split(error, batch.take(half), batch.drop(half), head)
    }

    /**
     * Posts [first] on its own; [second] is planned again. What lands proves the server up, so a repeat of [error]
     * can be pinned on an item. If [first] is rejected, only its error is handled: earlier changes go first, so a
     * later one can't be blamed for what an earlier one blocks. An ambiguous rejection (a 5xx, a 403 without an
     * index) before anything landed proves nothing: [second] is tried, and if it fails that way too, it is an outage
     * or a scope error. A refused control of a 500 at a known index means the server answers: the item is held.
     */
    private suspend fun split(error: SyncHttpException, first: List<Planned>, second: List<Planned>, head: Long): Sent {
        logger.i { "Posting ${first.size} of ${first.size + second.size} on their own (${error.status})" }
        val rejection = when (val posted = post(first, head)) {
            is Posted.Ok -> return landed(posted, Sent.Changed(posted.head))
            is Posted.Rejected -> posted.error
        }
        return when {
            rejection.code == SyncErrors.OUT_OF_SYNC -> Sent.Abort(PushOutcome.Restart)
            ambiguous(rejection) && !memory.landed -> when (val posted = post(second, head)) {
                is Posted.Ok -> landed(posted, Sent.Changed(posted.head))
                is Posted.Rejected -> when {
                    posted.error.code == SyncErrors.OUT_OF_SYNC -> Sent.Abort(PushOutcome.Restart)
                    posted.error.status >= 500 -> Sent.Abort(PushOutcome.Failed(failure(posted.error), retryable = true))
                    ambiguous(posted.error) -> forbidden(posted.error)
                    else -> rejected(posted.error, second, head)
                }
            }
            error.status == 500 && error.mutationIndex != null -> hold(second.single(), error, head)
            else -> rejected(rejection, first, head)
        }
    }

    /** Errors that are an item's only once the server is proven up: a 5xx (outage?) or a 403 without an index (scope?). */
    private fun ambiguous(error: SyncHttpException) = error.status >= 500 || (error.status == 403 && error.mutationIndex == null)

    private suspend fun item(error: SyncHttpException, batch: List<Planned>, culprit: Planned?, head: Long): Sent {
        if (culprit == null) return bisect(error, batch, head)
        generation.transaction { itemFailed(culprit, error) }
        memory.progress += 1
        return Sent.Changed(head)
    }

    /** An item failing in a way one run can't attribute: held when it already failed like this in an earlier run. */
    private fun repeated(culprit: Planned, error: SyncHttpException, head: Long): Sent {
        if ((store.failures[failureKey(culprit)]?.takeIf { it.status == error.status }?.count ?: 0) > 0) return hold(culprit, error, head)
        generation.transaction { countFailure(culprit, error) }
        return Sent.Abort(PushOutcome.Failed(failure(error), retryable = true))
    }

    /** Holds [culprit] with the growing expiry (see [HOLD_MILLIS]). */
    private fun hold(culprit: Planned, error: SyncHttpException, head: Long): Sent {
        generation.transaction { putHold(culprit, error, now() + HOLD_MILLIS[minOf(countFailure(culprit, error), HOLD_MILLIS.size) - 1]) }
        memory.progress += 1
        return Sent.Changed(head)
    }

    private fun settle(planned: Planned, response: PostedMutation) {
        val name = planned.type.name
        when (planned.kind) {
            CREATE -> {
                val remoteId = planned.type.bindCreated(planned.state, response)
                    ?: error("$name CREATE for ${planned.localId} returned no resource id")
                store.put(name, SyncRow(planned.localId, remoteId, planned.state))
            }
            UPDATE -> store.put(name, SyncRow(planned.localId, planned.remoteId, planned.state))
            DELETE -> {
                store.remove(name, planned.localId)
                planner.forgetCoveredChildren(planned.type, planned.localId)
            }
        }
    }

    private fun itemFailed(planned: Planned, error: SyncHttpException) {
        val type = planned.type
        val row = store.row(type.name, planned.localId)
        when {
            // Gone on the server: treat it as deleted remotely. Edit beats delete, so an UPDATE becomes a CREATE.
            error.status == 404 && planned.kind == DELETE -> {
                store.remove(type.name, planned.localId)
                planner.forgetCoveredChildren(type, planned.localId)
            }
            error.status == 404 -> store.remove(type.name, planned.localId)
            // Forbidden for this item: revert to base. Known 403s are prevented up front (read-only collections).
            error.status == 403 && row?.base != null -> {
                logger.w { "${type.name} ${planned.localId}: reverting a forbidden ${planned.kind}" }
                if (planned.kind == DELETE) planner.restore(type, planned.localId, row.base) else type.update(planned.localId, row.base)
            }
            // A duplicate name may be freed by another device: a remote change to the type releases it (Reconciler);
            // a day is the backstop.
            error.code == SyncErrors.UNIQUE_VIOLATION -> putHold(planned, error, now() + UNIQUE_HOLD_MILLIS)
            else -> putHold(planned, error, until = null)
        }
    }

    private fun putHold(planned: Planned, error: SyncHttpException, until: Long?) {
        val type = planned.type
        val row = store.row(type.name, planned.localId)
        val reason = SyncErrors.holdReason(error)
        logger.w { "${type.name} ${planned.localId}: holding ${planned.kind} ($reason)" }
        // The working state, not a temporary step: a hold is released when working differs from what it holds.
        store.put(type.name, SyncRow(planned.localId, row?.remoteId, row?.base, reason, planned.holdState, until))
    }

    /** Counts a run in which [planned] failed with [error]'s status; returns the count (1 = the first). */
    private fun countFailure(planned: Planned, error: SyncHttpException): Int {
        val key = failureKey(planned)
        val count = (store.failures[key]?.takeIf { it.status == error.status }?.count ?: 0) + 1
        store.failures = store.failures + (key to ItemFailure(error.status, count))
        return count
    }

    /** It may be a scope error for the whole request: keep every change and retry, but not soon. */
    private fun forbidden(error: SyncHttpException) =
        Sent.Abort(PushOutcome.Failed("request forbidden: ${error.message}", retryable = true, FORBIDDEN_BACKOFF_MILLIS))

    private fun signature(batch: List<Planned>) = batch.joinToString { "${it.type.name}:${it.localId}:${it.kind}" }

    private fun failureKey(planned: Planned) = "${planned.type.name}:${planned.localId}"

    private fun failure(error: SyncHttpException) = "HTTP ${error.status} ${error.code ?: ""}".trim()

    companion object {
        internal const val MAX_PHASES = 12
        private const val MAX_MUTATIONS_PER_POST = 100
        private const val HOUR = 60 * 60 * 1000L
        /** How long an item that keeps failing waits: by the runs it failed in (1 h, 6 h, then 24 h). */
        val HOLD_MILLIS = listOf(HOUR, 6 * HOUR, 24 * HOUR)
        const val FORBIDDEN_BACKOFF_MILLIS = 6 * HOUR
        const val UNIQUE_HOLD_MILLIS = 24 * HOUR
    }
}
