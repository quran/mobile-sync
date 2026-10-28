package com.quran.shared.pipeline.sync

import com.quran.shared.pipeline.SyncError

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
 * Observes sync runs. A run spans its first attempt and every retry, and ends with exactly one of
 * [syncSucceeded], [syncSkipped], or [syncFailed], unless it is cancelled.
 *
 * Calls arrive on background threads.
 */
internal interface SyncRunObserver {
    /** A signed-in run is contacting the server. Retries report it again. */
    fun syncStarted() {}

    /** The run synced every resource. */
    fun syncSucceeded() {}

    /**
     * The run ended without a result: no one is signed in, or a logout or reset stopped it or
     * began while it ran.
     */
    fun syncSkipped() {}

    /** The run failed and has no retries left. */
    fun syncFailed(error: SyncError) {}
}

/** Starts and stops sync runs for [com.quran.shared.pipeline.QuranDataService]. */
internal interface SynchronizationClient {
    fun localDataUpdated()
    fun applicationStarted()
    fun triggerSyncImmediately()

    /** A user signed in: sync now, pushing even during a backoff. */
    fun signedIn()

    /** The stored session was restored (at launch): run the app start's sync now instead of after its delay. */
    fun sessionRestored()
    fun cancelSyncing()
    suspend fun cancelSyncingAndJoin()
}
