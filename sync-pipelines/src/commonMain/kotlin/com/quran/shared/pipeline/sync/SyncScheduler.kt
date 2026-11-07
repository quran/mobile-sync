package com.quran.shared.pipeline.sync

import co.touchlab.kermit.Logger
import com.quran.shared.pipeline.SyncNowResult
import com.quran.shared.pipeline.SyncRun
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
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How long [SyncScheduler] waits: [standardInterval] between periodic runs, [appRefreshInterval] after an app start,
 * [localDataModifiedInterval] after a local change, [foregroundInterval] after the app came to the foreground, and
 * [failureRetryingConfig] between retries. [SyncClient] drops a return to the foreground that comes less than
 * [foregroundSkipAfterSync] after a run that pulled the server's changes.
 */
internal data class SchedulerTimings(
    val standardInterval: Duration,
    val appRefreshInterval: Duration,
    val localDataModifiedInterval: Duration,
    val foregroundInterval: Duration,
    val foregroundSkipAfterSync: Duration,
    val failureRetryingConfig: FailureRetryingConfig
)

/**
 * Exponential backoff: the first retry waits [baseDelay], each next one [multiplier] times longer, for up to
 * [maximumRetries]. Each wait is spread at random by up to [jitter] of itself either way (0.5: from half to one and a
 * half times), so devices that failed together don't retry together, then capped at [maximumDelay]. [jitter] is from 0
 * to 1: more could make a wait negative.
 */
internal data class FailureRetryingConfig(
    val baseDelay: Duration,
    val multiplier: Double,
    val maximumRetries: Int,
    val jitter: Double = 0.0,
    val maximumDelay: Duration = Duration.INFINITE
) {
    init {
        require(jitter in 0.0..1.0) { "jitter must be from 0 to 1, was $jitter" }
    }
}

/**
 * Production timings: 30 s after app start, 5 s after a local change, 2 s after a return to the foreground (none
 * within a minute of a sync that pulled), 30 min periodic. Retries wait about 2, 6, 18 and 54 s, each ±50% and at most
 * 60 s.
 */
internal val DefaultSyncTimings = SchedulerTimings(
    standardInterval = 30.minutes,
    appRefreshInterval = 30.seconds,
    localDataModifiedInterval = 5.seconds,
    foregroundInterval = 2.seconds,
    foregroundSkipAfterSync = 60.seconds,
    failureRetryingConfig = FailureRetryingConfig(
        baseDelay = 2.seconds,
        multiplier = 3.0,
        maximumRetries = 4,
        jitter = 0.5,
        maximumDelay = 60.seconds
    )
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

/**
 * Waits for the end of the run an immediate trigger started ([SyncScheduler.triggerAndAwait]), as
 * [com.quran.shared.pipeline.QuranDataService.syncNow] reports it. The first report wins; later ones are ignored, so an
 * attempt reports the end it knows best and the scheduler's fallback ([SyncNowResult.Unavailable]) changes nothing.
 */
internal class RunWaiter {
    private val result = CompletableDeferred<SyncNowResult>()

    fun report(result: SyncNowResult) {
        this.result.complete(result)
    }

    /** Selects the first report. */
    val onReport get() = result.onAwait
}

/** Reports [result] to each waiter. */
internal fun List<RunWaiter>.report(result: SyncNowResult) = forEach { it.report(result) }

/** Reports [run] as the run's end, when there is one. */
internal fun List<RunWaiter>.reportEnded(run: SyncRun?) {
    if (run != null) report(SyncNowResult.Ended(run))
}

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
 * with an immediate run. Otherwise an immediate trigger runs at once as itself, in place of a scheduled run that isn't
 * immediate, even one already due; while an immediate run is due, it joins that one. An app start (or a restored
 * session) during a run, and any trigger but an immediate one while a retry waits, is dropped. A failed attempt is retried with exponential backoff, jittered and capped as
 * [FailureRetryingConfig] says, up to the configured number of retries, unless it is not retryable or asks for a longer
 * backoff. After every run, whatever its outcome, the next one is scheduled after the periodic interval, so sync never
 * stops after its retries run out.
 *
 * [triggerAndAwait] waits for the run an immediate trigger asked for: the next run to start, which is the one after a
 * run in flight. Its first attempt reports how it went ([run]'s `waiters`): it ended, or it failed and waits to retry,
 * which ends the wait too. Waiters of a run that never starts, as a cancel or [close] drops it, get
 * [SyncNowResult.Unavailable].
 */
internal class SyncScheduler(
    private val timings: SchedulerTimings,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /**
     * Runs one attempt. [retryDelay] says how long until a failure of it is retried, or null when it won't be, so it
     * can report a final failure or the wait for the retry. A run's first attempt gets the [triggerAndAwait] waiters
     * for the run (none for a retry), and reports to them how it ended or that it waits to retry.
     */
    private val run: suspend (
        attempt: SyncAttempt,
        waiters: List<RunWaiter>,
        retryDelay: (AttemptOutcome.Failed) -> Duration?
    ) -> AttemptOutcome,
    /** A uniform sample in `0 until 1` for each attempt's jitter; tests pass a fixed one. */
    private val random: () -> Double = { Random.nextDouble() }
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
    /** [awaitIdle] callers waiting for the running attempt to finish. */
    private val idleWaiters = mutableListOf<CompletableDeferred<Unit>>()
    /** [triggerAndAwait] callers waiting for the next run to start, which they join. */
    private val runWaiters = mutableListOf<RunWaiter>()
    /** The waiters the running attempt reports to. */
    private var runningWaiters = emptyList<RunWaiter>()

    init {
        scope.launch { for (command in commands) handle(command) }
    }

    fun trigger(trigger: SyncTrigger) {
        commands.trySend(Command.Trigger(trigger, waiter = null))
    }

    /**
     * [trigger]s the immediate [trigger] and waits for the run it asked for: the next run to start, so one in flight
     * finishes first and the run right after it is waited for. Calls before that run starts all join it. Returns once
     * its first attempt reports how it went: the run ended, or it failed and waits to retry. A run that never starts (a
     * cancel or [close] dropped it) returns [SyncNowResult.Unavailable]. Cancelling the caller stops only the wait.
     */
    suspend fun triggerAndAwait(trigger: SyncTrigger): SyncNowResult {
        require(trigger.isImmediate) { "Only an immediate trigger starts the next run: $trigger" }
        val waiter = RunWaiter()
        if (commands.trySend(Command.Trigger(trigger, waiter)).isFailure) return SyncNowResult.Unavailable
        return select {
            waiter.onReport { it }
            scope.coroutineContext.job.onJoin { SyncNowResult.Unavailable }
        }
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

    /**
     * Waits until no attempt is running: one running now finishes and this scheduler has handled its end (scheduled
     * what comes next). An attempt reports its end to observers before that, so a trigger right after a run's end may
     * otherwise still find it running. Returns at once after [close].
     */
    internal suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        if (commands.trySend(Command.AwaitIdle(done)).isSuccess) {
            select {
                done.onAwait {}
                scope.coroutineContext.job.onJoin {}
            }
        }
    }

    /** Stops for good: the command loop, the timers and the running attempt. Later triggers do nothing. */
    fun close() {
        commands.close()
        scope.cancel()
    }

    private sealed interface Command {
        data class Trigger(val trigger: SyncTrigger, val waiter: RunWaiter?) : Command
        data class Fire(val token: Long, val attempt: SyncAttempt) : Command
        data class Finished(
            val generation: Long,
            val attempt: SyncAttempt,
            val jitterSample: Double,
            val outcome: AttemptOutcome
        ) : Command
        data class Cancel(val done: CompletableDeferred<Unit>?) : Command
        data class AwaitIdle(val done: CompletableDeferred<Unit>) : Command
    }

    private suspend fun handle(command: Command) {
        when (command) {
            is Command.Trigger -> {
                command.waiter?.let { runWaiters += it }
                onTrigger(command.trigger)
            }
            // A timer replaced after it fired, or a cancelled one, must not start a second run.
            is Command.Fire -> if (command.token == timerToken && timer != null && running == null) start(command.attempt)
            is Command.Finished -> if (command.generation == generation) {
                onFinished(command.attempt, command.jitterSample, command.outcome)
            }
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
                // The attempt reported its end as it stopped, unless it was cancelled before it began.
                (runningWaiters + runWaiters).report(SyncNowResult.Unavailable)
                runningWaiters = emptyList()
                runWaiters.clear()
                releaseIdleWaiters()
                command.done?.complete(Unit)
            }
            is Command.AwaitIdle -> if (running == null) command.done.complete(Unit) else idleWaiters += command.done
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
            // An immediate trigger runs now as itself: a run already due but not started yet (its timer may run late,
            // as after a suspend) would otherwise take its place, unless that run is immediate too, which it shares.
            trigger.isImmediate -> {
                val shared = timerAttempt?.trigger?.isImmediate == true
                schedule(Duration.ZERO, SyncAttempt(trigger, 0), onlyIfSooner = shared)
            }
            else -> schedule(delayOf(trigger), SyncAttempt(trigger, 0), onlyIfSooner = true)
        }
    }

    private fun start(attempt: SyncAttempt) {
        timer = null
        timerAttempt = null
        val generation = generation
        // One sample per attempt, so the delay the attempt reports (Retrying.nextAttemptAt) is the one waited here.
        val jitterSample = random()
        // Only a run's first attempt is the run the waiters asked for; an immediate trigger replaces a waiting retry.
        val waiters = if (attempt.number == 0) runWaiters.toList().also { runWaiters.clear() } else emptyList()
        runningWaiters = waiters
        running = scope.launch {
            try {
                val outcome = run(attempt, waiters) { failed -> retryDelay(attempt, failed, jitterSample) }
                commands.send(Command.Finished(generation, attempt, jitterSample, outcome))
            } finally {
                // The attempt reported how it went; one that failed to (it threw) must not leave a caller waiting.
                waiters.report(SyncNowResult.Unavailable)
            }
        }
    }

    private fun onFinished(attempt: SyncAttempt, jitterSample: Double, outcome: AttemptOutcome) {
        running = null
        runningWaiters = emptyList()
        val immediate = immediateDuringRun
        val followUp = followUpDuringRun
        val backoff = (outcome as? AttemptOutcome.Failed)?.let { retryDelay(attempt, it, jitterSample) }
        if (immediate != null) {
            // A user asked to sync (or signed in) while this run was going: run (and push) again right away.
            immediateDuringRun = null
            followUpDuringRun = null
            schedule(Duration.ZERO, SyncAttempt(immediate, 0), onlyIfSooner = false)
        } else if (backoff != null) {
            followUpDuringRun = null
            logger.i { "Retrying sync in $backoff (attempt ${attempt.number + 1})" }
            schedule(backoff, attempt.copy(number = attempt.number + 1), onlyIfSooner = false)
        } else if (followUp != null) {
            followUpDuringRun = null
            schedule(delayOf(followUp), SyncAttempt(followUp, 0), onlyIfSooner = false)
        } else {
            schedule(timings.standardInterval, SyncAttempt(SyncTrigger.PERIODIC, 0), onlyIfSooner = false)
        }
        releaseIdleWaiters()
    }

    private fun releaseIdleWaiters() {
        idleWaiters.forEach { it.complete(Unit) }
        idleWaiters.clear()
    }

    /**
     * The backoff before [failed]'s retry, null when it isn't retried: exponential in [attempt]'s number, moved by the
     * jitter as [jitterSample] (`0 until 1`; 0.5 moves it nothing) says, and capped.
     */
    private fun retryDelay(attempt: SyncAttempt, failed: AttemptOutcome.Failed, jitterSample: Double): Duration? {
        val config = timings.failureRetryingConfig
        val retried = failed.retryable && failed.retryAfterMillis == null && attempt.number < config.maximumRetries
        if (!retried) return null
        val nominal = config.baseDelay * config.multiplier.pow(attempt.number)
        return minOf(nominal * (1 + config.jitter * (2 * jitterSample - 1)), config.maximumDelay)
    }

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
