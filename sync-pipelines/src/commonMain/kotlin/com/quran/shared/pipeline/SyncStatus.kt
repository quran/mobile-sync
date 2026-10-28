package com.quran.shared.pipeline

import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.pipeline.sync.SyncRunObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

/**
 * The state of sync in this app session, published by [QuranDataService.syncStatus].
 *
 * A sync that fails is retried a few times: it is [Syncing] while an attempt runs and [Retrying] while it waits for the
 * next one, until it succeeds ([Succeeded]) or gives up ([Failed]). A "sync now" (`triggerSync(immediately = true)`)
 * during the wait runs at once in place of the retry. A sync that ends without syncing, because no one is signed in or
 * a logout or reset stopped it, leaves the status as it was before that sync started; so does a sync that only pulls
 * while pushes back off ([SyncRunResult.PULLED_ONLY]), which keeps the failure that started the backoff.
 *
 * Status is not persisted, so it starts as [Idle] on every launch.
 */
sealed class SyncStatus {
    /** No sync has finished since launch, or a logout or reset cleared the previous result. */
    data object Idle : SyncStatus()

    /** A signed-in sync attempt is running: the first one, or a retry. */
    data object Syncing : SyncStatus()

    /**
     * The latest attempt failed at [failedAt] because of [error], and the sync tries again at [nextAttemptAt] on its
     * own; it becomes [Syncing] when it does. No attempt is running, so an app can offer "Try again"
     * (`triggerSync(immediately = true)`), which runs at once instead of waiting.
     *
     * [nextAttemptAt] can be in the past: the retry's timer doesn't run while the system suspends the app (as iOS does
     * in the background), so it fires late. Show such a time as "now" rather than as a time that went by.
     *
     * It can also show only for a moment, for a retry that never comes: when a "sync now" arrived while the failed
     * attempt ran, that sync runs right after it instead, and the status becomes [Syncing] at once.
     */
    data class Retrying(
        val failedAt: PlatformDateTime,
        val error: SyncError,
        val nextAttemptAt: PlatformDateTime
    ) : SyncStatus()

    /** The latest sync finished at [at]. */
    data class Succeeded(val at: PlatformDateTime) : SyncStatus()

    /** The latest sync gave up at [at] after its retries, because of [error]. */
    data class Failed(val at: PlatformDateTime, val error: SyncError) : SyncStatus()
}

internal class SyncStatusTracker : SyncRunObserver {
    private val mutableStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = mutableStatus.asStateFlow()

    /** The status to show when a run ends without a result. */
    @Volatile
    private var lastResult: SyncStatus = SyncStatus.Idle

    override fun syncStarted() {
        mutableStatus.value = SyncStatus.Syncing
    }

    override fun syncRetrying(failedAt: PlatformDateTime, error: SyncError, nextAttemptAt: PlatformDateTime) {
        mutableStatus.value = SyncStatus.Retrying(failedAt, error, nextAttemptAt)
    }

    override fun syncSkipped() {
        mutableStatus.value = lastResult
    }

    override fun syncEnded(run: SyncRun) {
        when (run.result) {
            SyncRunResult.SUCCEEDED -> publishResult(SyncStatus.Succeeded(run.endedAt))
            SyncRunResult.FAILED -> publishResult(SyncStatus.Failed(run.endedAt, checkNotNull(run.error)))
            // A pull during a push backoff keeps the failure that started the backoff.
            SyncRunResult.PULLED_ONLY -> mutableStatus.value = lastResult
            // The service is going away, an immediate run replaced a waiting retry (it reports syncing next), or the
            // session ended (a skip follows).
            SyncRunResult.CANCELLED -> Unit
        }
    }

    /** Forgets the previous result after a logout or reset. */
    fun reset() {
        publishResult(SyncStatus.Idle)
    }

    private fun publishResult(result: SyncStatus) {
        lastResult = result
        mutableStatus.value = result
    }
}
