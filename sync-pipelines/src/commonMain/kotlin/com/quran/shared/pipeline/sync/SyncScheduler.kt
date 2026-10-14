package com.quran.shared.pipeline.sync

import co.touchlab.kermit.Logger
import com.quran.shared.syncengine.scheduling.FailureRetryingConfig
import com.quran.shared.syncengine.scheduling.SchedulerTimings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Production timings: as the old scheduler's (30 s after app start, 5 s after a local change, 30 min periodic). */
internal val DefaultSyncTimings = SchedulerTimings(
    standardInterval = 30.minutes,
    appRefreshInterval = 30.seconds,
    localDataModifiedInterval = 5.seconds,
    failureRetryingConfig = FailureRetryingConfig(baseDelay = 200.milliseconds, multiplier = 2.5, maximumRetries = 5)
)

/** What asked for a sync. */
internal enum class SyncTrigger { APP_START, LOCAL_CHANGE, IMMEDIATE, PERIODIC }

/** One attempt of a sync run: the first is number 0, its retries count up. */
internal data class SyncAttempt(val trigger: SyncTrigger, val number: Int)

/** How an attempt ended. */
internal sealed interface AttemptOutcome {
    /** Synced, or skipped (no session, a reset): nothing to retry. */
    data object Done : AttemptOutcome

    /** [retryAfterMillis]: pushes back off at least that long (the scheduler doesn't retry it quickly). */
    data class Failed(val retryable: Boolean, val retryAfterMillis: Long? = null) : AttemptOutcome
}

/**
 * When syncs run. A trigger schedules a run after its delay ([SchedulerTimings]: an app start, a local change, or now),
 * unless one is already due sooner. Runs never overlap: a local change during a run runs again after it, and an
 * immediate trigger (a user's "sync now") during a run runs right after it; one while a retry waits replaces the retry
 * with an immediate run. An app start during a run or a pending retry is dropped. A failed attempt is retried with exponential backoff, up to
 * the configured number of retries, unless it is not retryable or asks for a longer backoff. After every run, whatever
 * its outcome, the next one is scheduled after the periodic interval, so sync never stops after its retries run out (S21).
 */
internal class SyncScheduler(
    private val timings: SchedulerTimings,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Runs one attempt. [willRetry] says whether a failure of it will be retried, so it can report a final failure. */
    private val run: suspend (attempt: SyncAttempt, willRetry: (AttemptOutcome.Failed) -> Boolean) -> AttemptOutcome
) {
    private val logger = Logger.withTag("SyncScheduler")
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val commands = Channel<Command>(Channel.UNLIMITED)

    // State, touched only by the command loop.
    private var generation = 0L
    private var timer: Job? = null
    private var timerToken = 0L
    private var timerAt = 0L
    private var timerAttempt: SyncAttempt? = null
    private var running: Job? = null
    private var localChangeDuringRun = false
    private var immediateDuringRun = false

    init {
        scope.launch { for (command in commands) handle(command) }
    }

    fun trigger(trigger: SyncTrigger) {
        commands.trySend(Command.Trigger(trigger))
    }

    /** Cancels the scheduled and running work; later triggers schedule again. */
    fun cancel() {
        commands.trySend(Command.Cancel(done = null))
    }

    /** [cancel], and waits until the running attempt, if any, has stopped. Returns at once after [close]. */
    suspend fun cancelAndJoin() {
        val done = CompletableDeferred<Unit>()
        if (commands.trySend(Command.Cancel(done)).isSuccess) {
            select {
                done.onAwait {}
                scope.coroutineContext.job.onJoin {}
            }
        }
        scope.coroutineContext.job.takeIf { it.isCancelled }?.join()
    }

    /** Stops for good: the command loop, the timers and the running attempt. Later triggers do nothing. */
    fun close() {
        commands.close()
        scope.cancel()
    }

    private sealed interface Command {
        data class Trigger(val trigger: SyncTrigger) : Command
        data class Fire(val token: Long, val attempt: SyncAttempt) : Command
        data class Finished(val generation: Long, val attempt: SyncAttempt, val outcome: AttemptOutcome) : Command
        data class Cancel(val done: CompletableDeferred<Unit>?) : Command
    }

    private suspend fun handle(command: Command) {
        when (command) {
            is Command.Trigger -> onTrigger(command.trigger)
            // A timer replaced after it fired, or a cancelled one, must not start a second run.
            is Command.Fire -> if (command.token == timerToken && timer != null && running == null) start(command.attempt)
            is Command.Finished -> if (command.generation == generation) onFinished(command.attempt, command.outcome)
            is Command.Cancel -> {
                generation += 1
                timerToken += 1
                timer?.cancel()
                timer = null
                timerAttempt = null
                running?.cancelAndJoin()
                running = null
                localChangeDuringRun = false
                immediateDuringRun = false
                command.done?.complete(Unit)
            }
        }
    }

    private fun onTrigger(trigger: SyncTrigger) {
        when {
            running != null -> when (trigger) {
                SyncTrigger.LOCAL_CHANGE -> localChangeDuringRun = true
                SyncTrigger.IMMEDIATE -> immediateDuringRun = true
                else -> Unit
            }
            (timerAttempt?.number ?: 0) > 0 -> if (trigger == SyncTrigger.IMMEDIATE) {
                logger.d { "Replacing the scheduled retry with an immediate run" }
                schedule(Duration.ZERO, SyncAttempt(trigger, 0), onlyIfSooner = false)
            } else {
                logger.d { "Ignoring $trigger: a retry is scheduled" }
            }
            else -> schedule(delayOf(trigger), SyncAttempt(trigger, 0), onlyIfSooner = true)
        }
    }

    private fun start(attempt: SyncAttempt) {
        timer = null
        timerAttempt = null
        val generation = generation
        running = scope.launch {
            val outcome = run(attempt) { failed -> willRetry(attempt, failed) }
            commands.send(Command.Finished(generation, attempt, outcome))
        }
    }

    private fun onFinished(attempt: SyncAttempt, outcome: AttemptOutcome) {
        running = null
        if (immediateDuringRun) {
            // A user asked to sync while this run was going: run (and push) again right away.
            immediateDuringRun = false
            localChangeDuringRun = false
            schedule(Duration.ZERO, SyncAttempt(SyncTrigger.IMMEDIATE, 0), onlyIfSooner = false)
        } else if (outcome is AttemptOutcome.Failed && willRetry(attempt, outcome)) {
            localChangeDuringRun = false
            val backoff = timings.failureRetryingConfig.let { it.baseDelay * it.multiplier.pow(attempt.number) }
            logger.i { "Retrying sync in $backoff (attempt ${attempt.number + 1})" }
            schedule(backoff, attempt.copy(number = attempt.number + 1), onlyIfSooner = false)
        } else if (localChangeDuringRun) {
            localChangeDuringRun = false
            schedule(delayOf(SyncTrigger.LOCAL_CHANGE), SyncAttempt(SyncTrigger.LOCAL_CHANGE, 0), onlyIfSooner = false)
        } else {
            schedule(timings.standardInterval, SyncAttempt(SyncTrigger.PERIODIC, 0), onlyIfSooner = false)
        }
    }

    private fun willRetry(attempt: SyncAttempt, failed: AttemptOutcome.Failed): Boolean =
        failed.retryable && failed.retryAfterMillis == null && attempt.number < timings.failureRetryingConfig.maximumRetries

    private fun schedule(delay: Duration, attempt: SyncAttempt, onlyIfSooner: Boolean) {
        val at = now() + delay.inWholeMilliseconds
        if (onlyIfSooner && timer != null && at >= timerAt) return
        timer?.cancel()
        timerAt = at
        timerAttempt = attempt
        val token = ++timerToken
        timer = scope.launch {
            delay(delay)
            commands.send(Command.Fire(token, attempt))
        }
    }

    private fun delayOf(trigger: SyncTrigger): Duration = when (trigger) {
        SyncTrigger.APP_START -> timings.appRefreshInterval
        SyncTrigger.LOCAL_CHANGE -> timings.localDataModifiedInterval
        SyncTrigger.IMMEDIATE -> Duration.ZERO
        SyncTrigger.PERIODIC -> timings.standardInterval
    }
}
