package com.quran.shared.pipeline.integration

import com.quran.shared.pipeline.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Records every value of the public `syncStatus` flow, so the harness can wait for "the next run":
 * a [SyncStatus.Syncing] after a mark, then the value that ends it. The collector is unconfined,
 * so it sees each value as the engine sets it.
 */
internal class SyncStatusRecorder(flow: StateFlow<SyncStatus>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val history = MutableStateFlow<List<SyncStatus>>(emptyList())

    init {
        scope.launch { flow.collect { value -> history.update { it + value } } }
    }

    /** A position in the history; runs that start after it count. */
    fun mark(): Int = history.value.size

    /** Waits until no run is in flight. */
    suspend fun awaitNotSyncing(timeoutMillis: Long = RUN_TIMEOUT_MILLIS) {
        withTimeout(timeoutMillis) { history.first { it.lastOrNull() !is SyncStatus.Syncing } }
    }

    /**
     * Waits for a run that starts after [mark] to end, and returns the status it ended with, or
     * null when [skipped] reports first that the run found no session. A skipped run never reports
     * [SyncStatus.Syncing], so the status flow alone cannot show it.
     */
    suspend fun awaitRunAfter(
        mark: Int,
        device: String,
        skipped: Flow<Boolean> = emptyFlow(),
        timeoutMillis: Long = RUN_TIMEOUT_MILLIS
    ): SyncStatus? = try {
        withTimeout(timeoutMillis) {
            merge(
                history.mapNotNull { runEnd(it, mark) }.map { RunEnd(it) },
                skipped.filter { it }.map { RunEnd(null) }
            ).first().status
        }
    } catch (timeout: TimeoutCancellationException) {
        throw AssertionError("$device: no sync run started after the mark ended within $timeoutMillis ms; statuses: ${history.value}", timeout)
    }

    private class RunEnd(val status: SyncStatus?)

    private fun runEnd(values: List<SyncStatus>, mark: Int): SyncStatus? {
        val start = (mark until values.size).firstOrNull { values[it] is SyncStatus.Syncing } ?: return null
        return values.drop(start + 1).firstOrNull { it !is SyncStatus.Syncing }
    }

    fun close() = scope.cancel()

    companion object {
        const val RUN_TIMEOUT_MILLIS = 30_000L
    }
}
