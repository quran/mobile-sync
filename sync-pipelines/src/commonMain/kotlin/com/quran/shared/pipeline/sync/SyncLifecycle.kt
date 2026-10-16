package com.quran.shared.pipeline.sync

import com.quran.shared.pipeline.SyncRun

/**
 * Lets sync runs and their requests check that no logout or reset began since they started.
 *
 * A run captures an epoch when it starts; a logout or reset moves the epoch on, and checking a stale epoch throws
 * [SyncOperationInvalidatedException].
 */
interface SyncLifecycleGate {
    fun canStartSync(): Boolean = true
    suspend fun captureSyncEpoch(): Long = 0L
    suspend fun checkSyncEpoch(epoch: Long) = Unit
    suspend fun admitSyncPost(epoch: Long) {
        checkSyncEpoch(epoch)
    }
    suspend fun <T> withValidSyncEpoch(epoch: Long, block: suspend () -> T): T {
        checkSyncEpoch(epoch)
        return block()
    }
}

/** A logout or reset began after the sync operation captured its epoch. */
internal class SyncOperationInvalidatedException(message: String) : Exception(message)

/**
 * Observes sync runs. A run spans its first attempt and every retry, and ends with exactly one of [syncSkipped] or
 * [syncEnded], unless it is cancelled during a logout or reset, which reports nothing.
 *
 * Calls arrive on background threads.
 */
internal interface SyncRunObserver {
    /** A signed-in run is contacting the server. Retries report it again. */
    fun syncStarted() {}

    /**
     * The run ended without a result, and isn't recorded: no one is signed in, the sync lifecycle is unavailable, or a
     * logout or reset stopped it or began while it ran.
     */
    fun syncSkipped() {}

    /**
     * The run ended as [run] says: it synced, pulled only (pushes back off), failed with no retries left, or was
     * cancelled.
     */
    fun syncEnded(run: SyncRun) {}
}

/** Tells [observers] about each run in order, so the history records a run before `syncStatus` shows its end. */
internal class SyncRunObservers(private vararg val observers: SyncRunObserver) : SyncRunObserver {
    override fun syncStarted() = observers.forEach { it.syncStarted() }

    override fun syncSkipped() = observers.forEach { it.syncSkipped() }

    override fun syncEnded(run: SyncRun) = observers.forEach { it.syncEnded(run) }
}

/** Starts and stops sync runs for [com.quran.shared.pipeline.QuranDataService]. */
internal interface SynchronizationClient {
    fun localDataUpdated()
    fun applicationStarted()
    fun triggerSyncImmediately()

    /** A session became available (a sign-in, or the saved one at launch): sync now, pushing even during a backoff. */
    fun signedIn()
    fun cancelSyncing()
    suspend fun cancelSyncingAndJoin()
}
