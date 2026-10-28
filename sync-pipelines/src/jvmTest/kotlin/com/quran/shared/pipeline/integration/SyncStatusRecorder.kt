package com.quran.shared.pipeline.integration

import com.quran.shared.auth.model.AuthState
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Records every value of the public `syncStatus` flow, so the harness can wait for "the next run":
 * a [SyncStatus.Syncing] after a mark, then the value that ends it. [SyncStatus.Retrying] between
 * a run's attempts doesn't end it. The collector is unconfined
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

    /**
     * Waits until no attempt is running. A run waiting to retry ([SyncStatus.Retrying]) doesn't
     * count: a "sync now" replaces its retry, so the next run still starts after this.
     */
    suspend fun awaitNotSyncing(device: String) = within(RUN_TIMEOUT, "$device: a sync run already in flight did not end") {
        history.first { it.lastOrNull() !is SyncStatus.Syncing }
    }

    /**
     * Waits for a run that starts after [mark] and returns the status it ended with, or null when
     * [skipped] reports first that the run found no session.
     *
     * When [retrigger] is set, it runs again every [RETRIGGER_INTERVAL] until a run starts. A
     * scheduler may drop an immediate trigger that arrives while it is still finishing the previous
     * run, after that run already published its end, and then nothing runs. A scheduler that
     * remembers such a trigger starts the run on the first one, so it is not repeated.
     *
     * A run must start within [START_TIMEOUT] and end within [RUN_TIMEOUT]; otherwise this throws
     * [HarnessTimeoutException]. A run the sync lifecycle gate skips (a logout or reset in
     * progress) never reports [SyncStatus.Syncing] and never asks for headers, so it surfaces as a
     * start timeout.
     */
    suspend fun awaitRunAfter(
        mark: Int,
        device: String,
        skipped: Flow<Boolean>,
        retrigger: (suspend () -> Unit)? = null
    ): SyncStatus? {
        val startedOrSkipped = merge(history.filter { start(it, mark) != null }.map { true }, skipped.filter { it }.map { false })
        val started = within(
            START_TIMEOUT,
            "$device: no sync run started within $START_TIMEOUT. The device may have no session, or a " +
                "logout or reset may hold the sync lifecycle gate; such runs are skipped silently"
        ) {
            if (retrigger == null) return@within startedOrSkipped.first()
            var result: Boolean? = null
            while (result == null) {
                result = withTimeoutOrNull(RETRIGGER_INTERVAL) { startedOrSkipped.first() }
                if (result == null) retrigger()
            }
            result
        }
        if (!started) return null
        return within(RUN_TIMEOUT, "$device: a sync run did not end within $RUN_TIMEOUT") {
            history.mapNotNull { runEnd(it, mark) }.first()
        }
    }

    /** Waits until [authState] no longer shows a session, as after a run skipped because the session ended. */
    suspend fun awaitSignedOut(device: String, authState: StateFlow<AuthState>) =
        within(RUN_TIMEOUT, "$device: a run was skipped for no session, but the app still showed one after $RUN_TIMEOUT") {
            authState.first { it !is AuthState.Success }
        }

    /** Only this wait's own timeout becomes [HarnessTimeoutException]; an outer cancellation passes through. */
    private suspend fun <T : Any> within(timeout: Duration, message: String, block: suspend () -> T): T =
        withTimeoutOrNull(timeout) { block() } ?: throw HarnessTimeoutException("$message; statuses: ${history.value}")

    private fun start(values: List<SyncStatus>, mark: Int): Int? =
        (mark until values.size).firstOrNull { values[it] is SyncStatus.Syncing }

    private fun runEnd(values: List<SyncStatus>, mark: Int): SyncStatus? {
        val start = start(values, mark) ?: return null
        return values.drop(start + 1).firstOrNull { it !is SyncStatus.Syncing && it !is SyncStatus.Retrying }
    }

    fun close() = scope.cancel()

    companion object {
        val START_TIMEOUT = 3.seconds
        val RUN_TIMEOUT = 15.seconds
        val RETRIGGER_INTERVAL = 500.milliseconds
    }
}
