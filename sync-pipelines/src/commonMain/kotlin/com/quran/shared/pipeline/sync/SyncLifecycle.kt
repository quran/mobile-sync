package com.quran.shared.pipeline.sync

import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.pipeline.SyncError
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
 * Observes sync runs. A run spans its first attempt and every retry, reports [syncRetrying] after each failed attempt
 * that will be retried (and [syncStarted] when the retry starts), and ends with exactly one of [syncSkipped] or
 * [syncEnded], unless it is cancelled during a logout or reset, which reports nothing. A run the session's end stops
 * after it reached the server (a refused token refresh, or a retry that finds no session) reports both, in order:
 * [syncEnded], cancelled, so the history keeps what it did, then [syncSkipped], as for any run without a session.
 *
 * Calls arrive on background threads.
 */
internal interface SyncRunObserver {
    /** A signed-in run is contacting the server. Retries report it again. */
    fun syncStarted() {}

    /**
     * An attempt failed at [failedAt] with [error] and will be retried at [nextAttemptAt]. The run stays open: a "sync
     * now" or a sign-in replaces the retry (the run then ends cancelled, and the new run reports [syncStarted] at
     * once), and the retry reports [syncStarted]. One that arrived while the failed attempt ran replaces it too, so
     * this can report a retry that never comes; the immediate run follows at once.
     */
    fun syncRetrying(failedAt: PlatformDateTime, error: SyncError, nextAttemptAt: PlatformDateTime) {}

    /**
     * The run ended without a result: no one is signed in, the sync lifecycle is unavailable, or a logout or reset
     * stopped it or began while it ran.
     */
    fun syncSkipped() {}

    /**
     * The run ended as [run] says: it synced, pulled only (pushes back off), failed with no retries left, or was
     * cancelled.
     */
    fun syncEnded(run: SyncRun) {}
}

/** Tells each of [observers] about each run, in order. */
internal class SyncRunObservers(private vararg val observers: SyncRunObserver) : SyncRunObserver {
    override fun syncStarted() = observers.forEach { it.syncStarted() }

    override fun syncRetrying(failedAt: PlatformDateTime, error: SyncError, nextAttemptAt: PlatformDateTime) =
        observers.forEach { it.syncRetrying(failedAt, error, nextAttemptAt) }

    override fun syncSkipped() = observers.forEach { it.syncSkipped() }

    override fun syncEnded(run: SyncRun) = observers.forEach { it.syncEnded(run) }
}

/** Starts and stops sync runs for [com.quran.shared.pipeline.QuranDataService]. */
internal interface SynchronizationClient {
    fun localDataUpdated()

    /** The app came back to the foreground: sync after a short delay, like a local change. */
    fun appForegrounded()
    fun applicationStarted()
    fun triggerSyncImmediately()

    /** A user signed in: sync now, pushing even during a backoff. */
    fun signedIn()

    /** The stored session was restored (at launch): run the app start's sync now instead of after its delay. */
    fun sessionRestored()
    fun cancelSyncing()
    suspend fun cancelSyncingAndJoin()
}
