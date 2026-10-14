package com.quran.shared.pipeline.integration

import com.quran.shared.pipeline.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Records every value of the public `syncStatus` flow, so the harness can wait for "the next run":
 * a [SyncStatus.Syncing] after a mark, then the value that ends it. The collector is unconfined
 * and subscribes in the constructor, so it sees each value as the engine sets it.
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
    suspend fun awaitNotSyncing(device: String) = within(RUN_TIMEOUT, "$device: a sync run already in flight did not end") {
        history.first { it.lastOrNull() !is SyncStatus.Syncing }
    }

    /**
     * Waits for a run that starts after [mark] and returns the status it ended with, or null when
     * [skipped] reports first that the run found no session.
     *
     * A run must start within [START_TIMEOUT] and end within [RUN_TIMEOUT]; otherwise this throws
     * [HarnessTimeoutException]. A run the sync lifecycle gate skips (a logout or reset in
     * progress) never reports [SyncStatus.Syncing] and never asks for headers, so it surfaces as a
     * start timeout.
     */
    suspend fun awaitRunAfter(mark: Int, device: String, skipped: Flow<Boolean>): SyncStatus? {
        val started = within(
            START_TIMEOUT,
            "$device: no sync run started within $START_TIMEOUT. The device may have no session, or a " +
                "logout or reset may hold the sync lifecycle gate; such runs are skipped silently"
        ) {
            merge(history.filter { start(it, mark) != null }.map { true }, skipped.filter { it }.map { false }).first()
        }
        if (!started) return null
        return within(RUN_TIMEOUT, "$device: a sync run did not end within $RUN_TIMEOUT") {
            history.mapNotNull { runEnd(it, mark) }.first()
        }
    }

    /** Only this wait's own timeout becomes [HarnessTimeoutException]; an outer cancellation passes through. */
    private suspend fun <T : Any> within(timeout: Duration, message: String, block: suspend () -> T): T =
        withTimeoutOrNull(timeout) { block() } ?: throw HarnessTimeoutException("$message; statuses: ${history.value}")

    private fun start(values: List<SyncStatus>, mark: Int): Int? =
        (mark until values.size).firstOrNull { values[it] is SyncStatus.Syncing }

    private fun runEnd(values: List<SyncStatus>, mark: Int): SyncStatus? {
        val start = start(values, mark) ?: return null
        return values.drop(start + 1).firstOrNull { it !is SyncStatus.Syncing }
    }

    fun close() = scope.cancel()

    companion object {
        val START_TIMEOUT = 3.seconds
        val RUN_TIMEOUT = 15.seconds
    }
}
