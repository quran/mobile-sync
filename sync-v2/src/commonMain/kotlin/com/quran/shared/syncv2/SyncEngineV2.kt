package com.quran.shared.syncv2

import co.touchlab.kermit.Logger
import com.quran.shared.syncv2.db.SyncV2Database
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

sealed interface SyncResult {
    /**
     * Changes this run pushed; local rows it changed to take remote changes ([Reconciler.pulled]); children it dropped
     * because their parent was gone (a bug if ever above 0).
     */
    val pushed: Int
    val pulled: Int
    val droppedOrphans: Int

    data class Success(
        val held: List<HeldItem>,
        override val pushed: Int = 0,
        override val droppedOrphans: Int = 0,
        override val pulled: Int = 0
    ) : SyncResult
    /**
     * [retryAfterMillis]: a minimum backoff the scheduler should honour before pushing again (a 403 that may be a
     * scope error); pulling may go on. [cause]: what failed, such as a [SyncHttpException] or a network error.
     */
    data class Failed(
        val reason: String,
        val retryable: Boolean,
        override val pushed: Int = 0,
        val retryAfterMillis: Long? = null,
        override val droppedOrphans: Int = 0,
        val cause: Throwable? = null,
        override val pulled: Int = 0
    ) : SyncResult
}

/**
 * The generic sync: fetch, merge, push. The only state is the domain rows, the per-item base, the token and
 * the outbox, so every step is idempotent and a crash anywhere is repaired by the next run.
 * [types] must be in dependency order (parents first). [version] is the app and library version: holds are
 * re-evaluated when it changes. One sync runs at a time; concurrent calls queue.
 */
class SyncEngineV2(
    private val database: SyncV2Database,
    private val api: SyncApi,
    private val types: List<SyncType>,
    private val version: String,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) {
    private val logger = Logger.withTag("SyncEngineV2")
    val store = SyncStore(database)
    private val refs = object : Refs {
        override fun remoteId(type: SyncType, localId: Long): String? = store.row(type.name, localId)?.remoteId

        override fun localId(type: SyncType, remoteId: String): Long? = store.rowByRemote(type.name, remoteId)?.localId
    }
    private val planner = PushPlanner(store, types, refs)
    private val holds = Holds(store, types, refs)
    private val mutex = Mutex()

    /**
     * Syncs [account]: fetch, merge, then push unless [push] is false (a pull only, for example while pushes back off
     * after a 403 that may be a scope error).
     */
    suspend fun sync(account: String, push: Boolean = true): SyncResult {
        // Read before queueing: a wipe while this call waits for the mutex means the caller asked for a sync of
        // data that is gone (a logout), so it must not download that account again. A sync queued behind one that
        // switched to the same account (which wipes too) still runs.
        val calledAt = store.epoch
        return mutex.withLock { syncLocked(account, calledAt, push) }
    }

    private suspend fun syncLocked(account: String, calledAt: Long, push: Boolean): SyncResult {
        if (store.epoch != calledAt && store.account != account) {
            logger.i { "Sync skipped: local data was wiped while it was queued" }
            return SyncResult.Failed(SyncSupersededException().message!!, retryable = true, cause = SyncSupersededException())
        }
        return try {
            runRounds(account, start(account), push)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            failure(error)
        }
    }

    /**
     * Wipes all synced data now (logout), without waiting for a sync in flight: that sync can no longer commit,
     * so it can't bring the data back.
     */
    fun wipe() = wipe(database, types)

    private fun failure(error: Exception): SyncResult = when (error) {
        is SyncSupersededException -> {
            logger.i { "Sync abandoned: ${error.message}" }
            SyncResult.Failed(error.message!!, retryable = true, cause = error)
        }
        is SyncHttpException -> {
            logger.w { "Sync failed: ${error.message}" }
            SyncResult.Failed(error.message ?: "HTTP ${error.status}", retryable = SyncErrors.isRetryable(error.status), cause = error)
        }
        else -> {
            // Network errors: the outbox (if any) stays, and the next run resolves it.
            logger.w { "Sync failed: $error" }
            SyncResult.Failed(error.toString(), retryable = true, cause = error)
        }
    }

    private suspend fun runRounds(account: String, generation: Generation, push: Boolean): SyncResult {
        val memory = PushMemory()
        generation.transaction { holds.review(version, now()) }
        repeat(MAX_ROUNDS) {
            val changes = api.get(store.token)
            generation.transaction {
                OutboxResolver(store, types, refs, planner).resolve(changes.entries, account)
                val reconciler = Reconciler(store, types, refs)
                reconciler.reconcile(changes.entries)
                memory.droppedOrphans += reconciler.droppedOrphans
                memory.pulled += reconciler.pulled
                store.token = changes.head
            }
            if (!push) return SyncResult.Success(held(), droppedOrphans = memory.droppedOrphans, pulled = memory.pulled)
            when (val outcome = Pusher(generation, store, api, planner, account, memory, now).push(changes.head)) {
                PushOutcome.Done -> return SyncResult.Success(held(), memory.pushed, memory.droppedOrphans, memory.pulled)
                PushOutcome.Restart -> logger.i { "Restarting sync round" }
                PushOutcome.MorePending -> logger.i { "Pushed ${memory.pushed} so far; more local changes are pending" }
                is PushOutcome.Failed -> return SyncResult.Failed(
                    outcome.reason, outcome.retryable, memory.pushed, outcome.retryAfterMillis, memory.droppedOrphans, outcome.cause,
                    memory.pulled
                )
            }
        }
        return SyncResult.Failed(
            MORE_PENDING, retryable = true, memory.pushed, droppedOrphans = memory.droppedOrphans, pulled = memory.pulled
        )
    }

    /**
     * Switches to [account] and returns the generation this sync commits under, in one transaction. A different
     * account wipes everything first; only resetting bases would turn A's rows into CREATEs in B.
     */
    private fun start(account: String): Generation = database.transactionWithResult {
        val previous = store.account
        // No account but a token: sync state outlived a wipe. Treat it as another account's.
        if (previous != account && (previous != null || store.token != 0L)) {
            logger.i { "Account changed: wiping local data" }
            wipeAll(store, types)
        }
        store.account = account
        Generation(database, store, store.epoch)
    }

    /** Held items and the items waiting on them. */
    fun held(): List<HeldItem> = holds.list()

    companion object {
        /**
         * Wipes every synced item, its sync state, the token, the account, the outbox and the sync history, in one
         * transaction, and bumps the wipe epoch, so a sync in flight can't commit (logout, a managed reset). [types] in
         * dependency order.
         */
        fun wipe(database: SyncV2Database, types: List<SyncType>) = database.transaction { wipeAll(SyncStore(database), types) }

        private fun wipeAll(store: SyncStore, types: List<SyncType>) {
            val epoch = store.epoch
            types.reversed().forEach { it.wipe() }
            store.wipe()
            store.epoch = epoch + 1
        }

        internal const val MAX_ROUNDS = 5
        /** The reason of a run that made progress every phase but ran out of rounds (edits kept coming). */
        const val MORE_PENDING = "more changes than one sync can settle"
    }
}
