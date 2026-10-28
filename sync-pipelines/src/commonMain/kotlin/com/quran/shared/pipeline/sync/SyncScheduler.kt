package com.quran.shared.pipeline.sync

import co.touchlab.kermit.Logger
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

/**
 * How long [SyncScheduler] waits: [standardInterval] between periodic runs, [appRefreshInterval] after an app start,
 * [localDataModifiedInterval] after a local change, [foregroundInterval] after the app came to the foreground, and
 * [failureRetryingConfig] between retries.
 */
internal data class SchedulerTimings(
    val standardInterval: Duration,
    val appRefreshInterval: Duration,
    val localDataModifiedInterval: Duration,
    val foregroundInterval: Duration,
    val failureRetryingConfig: FailureRetryingConfig
)

/** Exponential backoff: the first retry waits [baseDelay], each next one [multiplier] times longer, for up to [maximumRetries]. */
internal data class FailureRetryingConfig(
    val baseDelay: Duration,
    val multiplier: Double,
    val maximumRetries: Int
)

/** Production timings: 30 s after app start, 5 s after a local change or a return to the foreground, 30 min periodic. */
internal val DefaultSyncTimings = SchedulerTimings(
    standardInterval = 30.minutes,
    appRefreshInterval = 30.seconds,
    localDataModifiedInterval = 5.seconds,
    foregroundInterval = 5.seconds,
    failureRetryingConfig = FailureRetryingConfig(baseDelay = 200.milliseconds, multiplier = 2.5, maximumRetries = 5)
)

/**
 * What asked for a sync. [IMMEDIATE] (a "sync now") and [SIGN_IN] (a user signed in) are immediate: they run at once,
 * run again right after a run in flight, replace a waiting retry, and push even while pushes back off.
 * [SESSION_RESTORED] (the stored session was restored, as at launch) is the app start's run brought forward: it runs
 * at once in place of a pending app start (or any later run), and like [APP_START] is dropped during a run or a retry.
 * [FOREGROUND] (the app came back to the foreground) is automatic like [LOCAL_CHANGE]: it runs after its delay unless a
 * run is due sooner, runs again after a run in flight, is dropped while a retry waits, and only pulls while pushes
 * back off.
 */
internal enum class SyncTrigger {
    APP_START, LOCAL_CHANGE, FOREGROUND, IMMEDIATE, PERIODIC, SIGN_IN, SESSION_RESTORED;

    val isImmediate: Boolean get() = this == IMMEDIATE || this == SIGN_IN

    /** Arriving during a run, it runs again after it: the run may have read the server, or the data, before it came. */
    val followsUpRun: Boolean get() = this == LOCAL_CHANGE || this == FOREGROUND
}

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
 * When syncs run. A trigger schedules a run after its delay ([SchedulerTimings]: an app start, a local change, a return
 * to the foreground, or now), unless one is already due sooner, which then keeps its own trigger. Runs never overlap: a
 * local change or a return to the foreground during a run runs again after it (once, as the first of them), and an
 * immediate trigger (a user's "sync now") during a run runs right after it; one while a retry waits replaces the retry
 * with an immediate run. An app start (or a restored session) during a run, and any trigger but an immediate one
 * while a retry waits, is dropped. A failed attempt is retried with exponential backoff, up to the configured number of
 * retries, unless it is not retryable or asks for a longer backoff. After every run, whatever its outcome, the next one
 * is scheduled after the periodic interval, so sync never stops after its retries run out.
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
    /** The first local change or return to the foreground during the run, to run again after it. */
    private var followUpDuringRun: SyncTrigger? = null
    /** The latest immediate trigger that arrived during the run, to run right after it. */
    private var immediateDuringRun: SyncTrigger? = null

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
                followUpDuringRun = null
                immediateDuringRun = null
                command.done?.complete(Unit)
            }
        }
    }

    private fun onTrigger(trigger: SyncTrigger) {
        when {
            running != null -> when {
                trigger.followsUpRun -> if (followUpDuringRun == null) followUpDuringRun = trigger
                trigger.isImmediate -> immediateDuringRun = trigger
                else -> Unit
            }
            (timerAttempt?.number ?: 0) > 0 -> if (trigger.isImmediate) {
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
        val immediate = immediateDuringRun
        val followUp = followUpDuringRun
        if (immediate != null) {
            // A user asked to sync (or signed in) while this run was going: run (and push) again right away.
            immediateDuringRun = null
            followUpDuringRun = null
            schedule(Duration.ZERO, SyncAttempt(immediate, 0), onlyIfSooner = false)
        } else if (outcome is AttemptOutcome.Failed && willRetry(attempt, outcome)) {
            followUpDuringRun = null
            val backoff = timings.failureRetryingConfig.let { it.baseDelay * it.multiplier.pow(attempt.number) }
            logger.i { "Retrying sync in $backoff (attempt ${attempt.number + 1})" }
            schedule(backoff, attempt.copy(number = attempt.number + 1), onlyIfSooner = false)
        } else if (followUp != null) {
            followUpDuringRun = null
            schedule(delayOf(followUp), SyncAttempt(followUp, 0), onlyIfSooner = false)
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
        SyncTrigger.FOREGROUND -> timings.foregroundInterval
        SyncTrigger.IMMEDIATE, SyncTrigger.SIGN_IN, SyncTrigger.SESSION_RESTORED -> Duration.ZERO
        SyncTrigger.PERIODIC -> timings.standardInterval
    }
}
