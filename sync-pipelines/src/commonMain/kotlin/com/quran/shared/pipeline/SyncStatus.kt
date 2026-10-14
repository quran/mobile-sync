package com.quran.shared.pipeline

import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.currentPlatformDateTime
import com.quran.shared.syncengine.SyncError
import com.quran.shared.syncengine.SyncRunObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

/**
 * The state of sync in this app session, published by [QuranDataService.syncStatus].
 *
 * A sync that fails is retried a few times and stays [Syncing] until it succeeds or gives up.
 * A sync that ends without syncing, because no one is signed in or a logout or reset stopped it,
 * leaves the status as it was before that sync started.
 *
 * Status is not persisted, so it starts as [Idle] on every launch.
 */
sealed class SyncStatus {
    /** No sync has finished since launch, or a logout or reset cleared the previous result. */
    data object Idle : SyncStatus()

    /** A signed-in sync is running or waiting to retry. */
    data object Syncing : SyncStatus()

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

    override fun syncSucceeded() {
        publishResult(SyncStatus.Succeeded(currentPlatformDateTime()))
    }

    override fun syncSkipped() {
        mutableStatus.value = lastResult
    }

    override fun syncFailed(error: SyncError) {
        publishResult(SyncStatus.Failed(currentPlatformDateTime(), error))
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
