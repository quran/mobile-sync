package com.quran.shared.syncv2

import co.touchlab.kermit.Logger
import com.quran.shared.syncv2.db.SyncV2Database
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

sealed interface SyncResult {
    /** Changes this run pushed; children it dropped because their parent was gone (a bug if ever above 0). */
    val pushed: Int
    val droppedOrphans: Int

    data class Success(val held: List<HeldItem>, override val pushed: Int = 0, override val droppedOrphans: Int = 0) : SyncResult
    /** [retryAfterMillis]: a minimum backoff the scheduler should honour (a 403 that may be a scope error). */
    data class Failed(
        val reason: String,
        val retryable: Boolean,
        override val pushed: Int = 0,
        val retryAfterMillis: Long? = null,
        override val droppedOrphans: Int = 0
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

    suspend fun sync(account: String): SyncResult {
        // Read before queueing: a wipe while this call waits for the mutex means the caller asked for a sync of
        // data that is gone (a logout), so it must not download that account again. A sync queued behind one that
        // switched to the same account (which wipes too) still runs.
        val calledAt = store.epoch
        return mutex.withLock { syncLocked(account, calledAt) }
    }

    private suspend fun syncLocked(account: String, calledAt: Long): SyncResult {
        if (store.epoch != calledAt && store.account != account) {
            logger.i { "Sync skipped: local data was wiped while it was queued" }
            return SyncResult.Failed(SyncSupersededException().message!!, retryable = true)
        }
        return try {
            runRounds(account, start(account))
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
    fun wipe() = database.transaction { wipeAll() }

    private fun failure(error: Exception): SyncResult = when (error) {
        is SyncSupersededException -> {
            logger.i { "Sync abandoned: ${error.message}" }
            SyncResult.Failed(error.message!!, retryable = true)
        }
        is SyncHttpException -> {
            logger.w { "Sync failed: ${error.message}" }
            SyncResult.Failed(error.message ?: "HTTP ${error.status}", retryable = SyncErrors.isRetryable(error.status))
        }
        else -> {
            // Network errors: the outbox (if any) stays, and the next run resolves it.
            logger.w { "Sync failed: $error" }
            SyncResult.Failed(error.toString(), retryable = true)
        }
    }

    private suspend fun runRounds(account: String, generation: Generation): SyncResult {
        val memory = PushMemory()
        generation.transaction { holds.review(version, now()) }
        repeat(MAX_ROUNDS) {
            val changes = api.get(store.token)
            generation.transaction {
                OutboxResolver(store, types, refs, planner).resolve(changes.entries, account)
                val reconciler = Reconciler(store, types, refs)
                reconciler.reconcile(changes.entries)
                memory.droppedOrphans += reconciler.droppedOrphans
                store.token = changes.head
            }
            when (val outcome = Pusher(generation, store, api, planner, account, memory, now).push(changes.head)) {
                PushOutcome.Done -> return SyncResult.Success(held(), memory.pushed, memory.droppedOrphans)
                PushOutcome.Restart -> logger.i { "Restarting sync round" }
                PushOutcome.MorePending -> logger.i { "Pushed ${memory.pushed} so far; more local changes are pending" }
                is PushOutcome.Failed ->
                    return SyncResult.Failed(outcome.reason, outcome.retryable, memory.pushed, outcome.retryAfterMillis, memory.droppedOrphans)
            }
        }
        return SyncResult.Failed(MORE_PENDING, retryable = true, memory.pushed, droppedOrphans = memory.droppedOrphans)
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
            wipeAll()
        }
        store.account = account
        Generation(database, store, store.epoch)
    }

    private fun wipeAll() {
        val epoch = store.epoch
        types.reversed().forEach { it.wipe() }
        store.wipe()
        store.epoch = epoch + 1
    }

    /** Held items and the items waiting on them. */
    fun held(): List<HeldItem> = holds.list()

    companion object {
        internal const val MAX_ROUNDS = 5
        /** The reason of a run that made progress every phase but ran out of rounds (edits kept coming). */
        const val MORE_PENDING = "more changes than one sync can settle"
    }
}
