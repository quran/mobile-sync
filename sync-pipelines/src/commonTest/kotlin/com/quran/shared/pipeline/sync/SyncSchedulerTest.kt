@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.quran.shared.pipeline.sync

import com.quran.shared.persistence.util.toPlatform
import com.quran.shared.pipeline.SyncNowResult
import com.quran.shared.pipeline.SyncRun
import com.quran.shared.pipeline.SyncRunResult
import com.quran.shared.pipeline.SyncRunTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/** [SyncScheduler] on virtual time: when runs start, how failures are retried, and that sync never stops. */
class SyncSchedulerTest {
    private val timings = SchedulerTimings(
        standardInterval = 30.minutes,
        appRefreshInterval = 30.seconds,
        localDataModifiedInterval = 5.seconds,
        foregroundInterval = 3.seconds,
        foregroundSkipAfterSync = Duration.ZERO,
        failureRetryingConfig = FailureRetryingConfig(baseDelay = 200.milliseconds, multiplier = 2.5, maximumRetries = 5)
    )

    /** Records each attempt (with the time it started) and answers it with the next scripted outcome. */
    private class Runs(private val scope: TestScope) {
        val attempts = mutableListOf<Pair<Long, SyncAttempt>>()
        val finalFailures = mutableListOf<Long>()
        /** The delay each failure that will be retried was told. */
        val retryDelays = mutableListOf<Duration>()
        val outcomes = ArrayDeque<AttemptOutcome>()
        var block: CompletableDeferred<Unit>? = null
        /** How many waiters each attempt got, in order. */
        val waiterCounts = mutableListOf<Int>()
        /** Thrown by the next attempt in place of an outcome, and before it reports to its waiters. */
        var throwing: Throwable? = null

        suspend fun run(attempt: SyncAttempt, waiters: List<RunWaiter>, retryDelay: (AttemptOutcome.Failed) -> Duration?): AttemptOutcome {
            attempts += scope.testScheduler.currentTime to attempt
            waiterCounts += waiters.size
            block?.await()
            throwing?.let {
                throwing = null
                throw it
            }
            val outcome = outcomes.removeFirstOrNull() ?: AttemptOutcome.Done
            if (outcome is AttemptOutcome.Failed) {
                val delay = retryDelay(outcome)
                if (delay == null) finalFailures += scope.testScheduler.currentTime else retryDelays += delay
            }
            waiters.report(reportOf(attempt))
            return outcome
        }
    }

    /** Added to virtual time for the scheduler's clock, as a user setting the time would move it. */
    private var clockOffset = 0L

    /** Runs [test] with a scheduler that is cancelled afterwards: its periodic runs would otherwise never end. */
    private fun schedulerTest(
        timings: SchedulerTimings = this.timings,
        random: () -> Double = { 0.5 },
        test: suspend TestScope.(Runs, SyncScheduler) -> Unit
    ): TestResult = runTest {
        val runs = Runs(this)
        val scheduler = SyncScheduler(timings, StandardTestDispatcher(testScheduler), { testScheduler.currentTime + clockOffset }, runs::run, random)
        try {
            test(runs, scheduler)
        } finally {
            scheduler.cancelAndJoin()
        }
    }

    @Test
    fun `a burst of local changes runs once after the local change delay`() = schedulerTest { runs, scheduler ->
        repeat(3) { scheduler.trigger(SyncTrigger.LOCAL_CHANGE) }
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(emptyList(), runs.attempts)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(5_000L to SyncAttempt(SyncTrigger.LOCAL_CHANGE, 0)), runs.attempts)
    }

    @Test
    fun `an immediate trigger runs at once and an app start after its delay`() = schedulerTest { runs, scheduler ->
        scheduler.trigger(SyncTrigger.IMMEDIATE)
        runCurrent()
        scheduler.trigger(SyncTrigger.APP_START)
        advanceTimeBy(30_001)
        runCurrent()

        assertEquals(listOf(0L to SyncTrigger.IMMEDIATE, 30_000L to SyncTrigger.APP_START), runs.attempts.map { it.first to it.second.trigger })
    }

    @Test
    fun `a failure is retried with backoff and reported once and the periodic sync goes on after the retries`() = schedulerTest { runs, scheduler ->
        repeat(6) { runs.outcomes += AttemptOutcome.Failed(retryable = true) }

        scheduler.trigger(SyncTrigger.IMMEDIATE)
        advanceTimeBy(10_000)
        runCurrent()

        // 200 ms × 2.5^n: 200, 500, 1250, 3125, 7812.5 (a delay rounds up to whole ms).
        assertEquals(listOf(0L, 200L, 700L, 1_950L, 5_075L, 12_888L).filter { it <= 10_000 }, runs.attempts.map { it.first })
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals((0..5).toList(), runs.attempts.map { it.second.number })
        assertEquals(listOf(12_888L), runs.finalFailures, "the last failure only")
        assertEquals(listOf(200.0, 500.0, 1_250.0, 3_125.0, 7_812.5), runs.retryDelays.map { it.toDouble(DurationUnit.MILLISECONDS) }, "each retry's wait, as the attempt is told it")

        advanceTimeBy(30.minutes.inWholeMilliseconds)
        runCurrent()
        assertEquals(SyncAttempt(SyncTrigger.PERIODIC, 0), runs.attempts.last().second, "S21: sync goes on")
    }

    /** Production's backoff: about 2, 6, 18 and 54 s, each spread by up to half of itself either way, at most a minute. */
    @Test
    fun `production retries wait about 2 then 6 then 18 then 54 s jittered by half and capped at a minute`() {
        fun delays(sample: Double): List<Duration> {
            var delays = emptyList<Duration>()
            schedulerTest(DefaultSyncTimings, random = { sample }) { runs, scheduler ->
                repeat(5) { runs.outcomes += AttemptOutcome.Failed(retryable = true) }
                scheduler.trigger(SyncTrigger.IMMEDIATE)
                advanceTimeBy(5.minutes.inWholeMilliseconds)
                runCurrent()
                assertEquals(5, runs.attempts.size, "the first attempt and four retries")
                assertEquals(1, runs.finalFailures.size)
                delays = runs.retryDelays.toList()
            }
            return delays
        }

        assertEquals(listOf(2.seconds, 6.seconds, 18.seconds, 54.seconds), delays(0.5), "no jitter")
        assertEquals(listOf(1.seconds, 3.seconds, 9.seconds, 27.seconds), delays(0.0), "half")
        assertEquals(listOf(3.seconds, 9.seconds, 27.seconds, 60.seconds), delays(1.0), "one and a half, the last capped")
    }

    @Test
    fun `a jitter outside 0 to 1 is refused`() {
        listOf(-0.1, 1.5, Double.NaN).forEach { jitter ->
            assertFailsWith<IllegalArgumentException>("$jitter") { FailureRetryingConfig(1.seconds, 2.0, 1, jitter = jitter) }
        }
        listOf(0.0, 1.0).forEach { FailureRetryingConfig(1.seconds, 2.0, 1, jitter = it) }
    }

    /** Each attempt samples its own jitter, and the delay it is told is the one the scheduler waits. */
    @Test
    fun `a jittered retry waits the delay the attempt was told`() {
        val samples = ArrayDeque(listOf(0.1, 0.9, 0.3))
        val jittered = timings.copy(failureRetryingConfig = FailureRetryingConfig(1.seconds, 2.0, 2, jitter = 0.5))
        schedulerTest(jittered, random = { samples.removeFirst() }) { runs, scheduler ->
            repeat(3) { runs.outcomes += AttemptOutcome.Failed(retryable = true) }

            scheduler.trigger(SyncTrigger.IMMEDIATE)
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals(listOf(600.milliseconds, 2_800.milliseconds), runs.retryDelays, "1 s × 0.6, then 2 s × 1.4")
            assertEquals(listOf(0L, 600L, 3_400L), runs.attempts.map { it.first }, "each retry after the delay it was told")
        }
    }

    @Test
    fun `a failure that asks for a long backoff is not retried soon`() = schedulerTest { runs, scheduler ->
        runs.outcomes += AttemptOutcome.Failed(retryable = true, retryAfterMillis = 60 * 60 * 1000L)

        scheduler.trigger(SyncTrigger.IMMEDIATE)
        advanceTimeBy(29.minutes.inWholeMilliseconds)
        runCurrent()

        assertEquals(1, runs.attempts.size)
        assertEquals(listOf(0L), runs.finalFailures)
        advanceTimeBy(1.minutes.inWholeMilliseconds + 1)
        runCurrent()
        assertEquals(SyncTrigger.PERIODIC, runs.attempts.last().second.trigger, "the periodic run still comes")
    }

    @Test
    fun `a non-retryable failure is not retried`() = schedulerTest { runs, scheduler ->
        runs.outcomes += AttemptOutcome.Failed(retryable = false)

        scheduler.trigger(SyncTrigger.IMMEDIATE)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, runs.attempts.size)
        assertEquals(listOf(0L), runs.finalFailures)
    }

    @Test
    fun `a local change during a run runs again after it`() = schedulerTest { runs, scheduler ->
        runs.block = CompletableDeferred()

        scheduler.trigger(SyncTrigger.IMMEDIATE)
        runCurrent()
        scheduler.trigger(SyncTrigger.APP_START)
        scheduler.trigger(SyncTrigger.LOCAL_CHANGE)
        runCurrent()
        runs.block!!.complete(Unit)
        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(listOf(SyncTrigger.IMMEDIATE, SyncTrigger.LOCAL_CHANGE), runs.attempts.map { it.second.trigger })
        assertEquals(5_000L, runs.attempts.last().first, "after the local change delay, counted from the run's end")
    }

    @Test
    fun `a return to the foreground runs after its delay in place of the periodic run`() = schedulerTest { runs, scheduler ->
        scheduler.trigger(SyncTrigger.IMMEDIATE)
        runCurrent()
        scheduler.trigger(SyncTrigger.FOREGROUND)
        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(1, runs.attempts.size)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(0L to SyncAttempt(SyncTrigger.IMMEDIATE, 0), 3_000L to SyncAttempt(SyncTrigger.FOREGROUND, 0)), runs.attempts)
    }

    /** The run due first keeps its trigger: a local change due sooner absorbs a later foreground, and the reverse. */
    @Test
    fun `a return to the foreground and a pending local change run once`() = schedulerTest { runs, scheduler ->
        scheduler.trigger(SyncTrigger.LOCAL_CHANGE)
        advanceTimeBy(2_500)
        scheduler.trigger(SyncTrigger.FOREGROUND)
        advanceTimeBy(5_000)
        runCurrent()
        scheduler.trigger(SyncTrigger.FOREGROUND)
        runCurrent()
        scheduler.trigger(SyncTrigger.LOCAL_CHANGE)
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(
            listOf(5_000L to SyncTrigger.LOCAL_CHANGE, 10_500L to SyncTrigger.FOREGROUND),
            runs.attempts.map { it.first to it.second.trigger }
        )
    }

    @Test
    fun `a return to the foreground during a run runs again after it`() = schedulerTest { runs, scheduler ->
        runs.block = CompletableDeferred()

        scheduler.trigger(SyncTrigger.IMMEDIATE)
        runCurrent()
        scheduler.trigger(SyncTrigger.FOREGROUND)
        scheduler.trigger(SyncTrigger.LOCAL_CHANGE)
        runCurrent()
        runs.block!!.complete(Unit)
        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(listOf(0L to SyncTrigger.IMMEDIATE, 3_000L to SyncTrigger.FOREGROUND), runs.attempts.map { it.first to it.second.trigger }, "once, as the first of them")
    }

    @Test
    fun `a return to the foreground while a retry waits is dropped`() = schedulerTest { runs, scheduler ->
        runs.outcomes += AttemptOutcome.Failed(retryable = true)

        scheduler.trigger(SyncTrigger.IMMEDIATE)
        runCurrent()
        scheduler.trigger(SyncTrigger.FOREGROUND)
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(listOf(0L to SyncAttempt(SyncTrigger.IMMEDIATE, 0), 200L to SyncAttempt(SyncTrigger.IMMEDIATE, 1)), runs.attempts)
    }

    /** A user's "sync now" during a run is not swallowed: it runs right after, ahead of a local change's delay. */
    @Test
    fun `an immediate trigger during a run runs right after it`() = schedulerTest { runs, scheduler ->
        runs.block = CompletableDeferred()

        scheduler.trigger(SyncTrigger.LOCAL_CHANGE)
        advanceTimeBy(5_001)
        runCurrent()
        scheduler.trigger(SyncTrigger.LOCAL_CHANGE)
        scheduler.trigger(SyncTrigger.IMMEDIATE)
        runCurrent()
        runs.block!!.complete(Unit)
        runCurrent()

        assertEquals(listOf(5_000L to SyncTrigger.LOCAL_CHANGE, 5_001L to SyncTrigger.IMMEDIATE), runs.attempts.map { it.first to it.second.trigger }, "right after the run, not after the local change delay")
    }

    @Test
    fun `an immediate trigger while a retry waits runs at once`() = schedulerTest { runs, scheduler ->
        runs.outcomes += AttemptOutcome.Failed(retryable = true)
        repeat(3) { runs.outcomes += AttemptOutcome.Failed(retryable = true) }

        scheduler.trigger(SyncTrigger.LOCAL_CHANGE)
        advanceTimeBy(5_001)
        runCurrent()
        scheduler.trigger(SyncTrigger.IMMEDIATE)
        runCurrent()

        assertEquals(listOf(5_000L to SyncAttempt(SyncTrigger.LOCAL_CHANGE, 0), 5_001L to SyncAttempt(SyncTrigger.IMMEDIATE, 0)), runs.attempts)
    }

    /** A sign-in is immediate like a "sync now": at once, right after a run in flight, and in place of a waiting retry. */
    @Test
    fun `a sign-in runs at once and after a run in flight and in place of a waiting retry`() = schedulerTest { runs, scheduler ->
        scheduler.trigger(SyncTrigger.SIGN_IN)
        runCurrent()
        runs.block = CompletableDeferred()
        scheduler.trigger(SyncTrigger.LOCAL_CHANGE)
        advanceTimeBy(5_001)
        runCurrent()
        scheduler.trigger(SyncTrigger.SIGN_IN)
        runs.outcomes += AttemptOutcome.Done
        runs.outcomes += AttemptOutcome.Failed(retryable = true)
        runs.block!!.complete(Unit)
        runs.block = null
        runCurrent()
        scheduler.trigger(SyncTrigger.SIGN_IN)
        runCurrent()

        assertEquals(
            listOf(
                0L to SyncAttempt(SyncTrigger.SIGN_IN, 0),
                5_000L to SyncAttempt(SyncTrigger.LOCAL_CHANGE, 0),
                5_001L to SyncAttempt(SyncTrigger.SIGN_IN, 0),
                5_001L to SyncAttempt(SyncTrigger.SIGN_IN, 0)
            ),
            runs.attempts,
            "the second sign-in ran right after the local change; the third replaced its retry"
        )
    }

    /** A restored session brings the app start's run forward: one run at once, none after the app start's delay. */
    @Test
    fun `a restored session runs the app start sync at once and is dropped during a run`() = schedulerTest { runs, scheduler ->
        scheduler.trigger(SyncTrigger.APP_START)
        runCurrent()
        runs.block = CompletableDeferred()
        scheduler.trigger(SyncTrigger.SESSION_RESTORED)
        runCurrent()
        scheduler.trigger(SyncTrigger.SESSION_RESTORED)
        runs.block!!.complete(Unit)
        runs.block = null
        advanceTimeBy(30_001)
        runCurrent()

        assertEquals(listOf(0L to SyncAttempt(SyncTrigger.SESSION_RESTORED, 0)), runs.attempts, "no second run, at once or at 30 s")
    }

    @Test
    fun `cancel and join stops the running attempt and what was scheduled while later triggers still run`() = schedulerTest { runs, scheduler ->
        runs.block = CompletableDeferred()
        scheduler.trigger(SyncTrigger.IMMEDIATE)
        runCurrent()
        scheduler.trigger(SyncTrigger.LOCAL_CHANGE)

        val join = launch { scheduler.cancelAndJoin() }
        runCurrent()
        assertTrue(join.isCompleted, "the running attempt was cancelled")
        advanceTimeBy(60.minutes.inWholeMilliseconds)
        runCurrent()
        assertEquals(1, runs.attempts.size, "nothing ran after the cancel")

        runs.block = null
        scheduler.trigger(SyncTrigger.IMMEDIATE)
        runCurrent()
        assertEquals(2, runs.attempts.size)
    }

    @Test
    fun `await idle returns once the running attempt's end is handled`() = schedulerTest { runs, scheduler ->
        runs.block = CompletableDeferred()
        scheduler.trigger(SyncTrigger.IMMEDIATE)
        runCurrent()

        val idle = launch { scheduler.awaitIdle() }
        runCurrent()
        assertTrue(!idle.isCompleted, "an attempt is running")
        runs.block!!.complete(Unit)
        runCurrent()

        assertTrue(idle.isCompleted)
        val again = launch { scheduler.awaitIdle() }
        runCurrent()
        assertTrue(again.isCompleted, "nothing runs: at once")
    }

    @Test
    fun `a waited trigger returns once its run's first attempt reports`() = schedulerTest { runs, scheduler ->
        runs.block = CompletableDeferred()
        val waited = async { scheduler.triggerAndAwait(SyncTrigger.IMMEDIATE) }
        runCurrent()
        assertTrue(!waited.isCompleted, "the attempt is running")

        runs.block!!.complete(Unit)
        runCurrent()

        assertEquals(reportOf(SyncAttempt(SyncTrigger.IMMEDIATE, 0)), waited.await())
        assertEquals(listOf(1), runs.waiterCounts)
    }

    /** One that asks during a run waits for the run right after it, not the one in flight. */
    @Test
    fun `a waited trigger during a run waits for the run after it`() = schedulerTest { runs, scheduler ->
        runs.block = CompletableDeferred()
        scheduler.trigger(SyncTrigger.LOCAL_CHANGE)
        advanceTimeBy(5_001)
        runCurrent()
        val waited = async { scheduler.triggerAndAwait(SyncTrigger.IMMEDIATE) }
        runCurrent()

        runs.block!!.complete(Unit)
        runs.block = CompletableDeferred()
        runCurrent()
        assertTrue(!waited.isCompleted, "the run after it is running")
        runs.block!!.complete(Unit)
        runCurrent()

        assertEquals(reportOf(SyncAttempt(SyncTrigger.IMMEDIATE, 0)), waited.await())
        assertEquals(listOf(0, 1), runs.waiterCounts, "the local change's run had no waiter; the sync now's had it")
    }

    /**
     * A run due by the clock whose timer hasn't fired (the clock moved on, or the timer runs late after a suspend) doesn't
     * take an immediate trigger's place: the waiter gets an immediate run at once, not the local change's later.
     */
    @Test
    fun `a waited trigger runs at once in place of a due local change whose timer hasn't fired`() = schedulerTest { runs, scheduler ->
        scheduler.trigger(SyncTrigger.LOCAL_CHANGE)
        runCurrent()
        clockOffset = 10_000

        val result = scheduler.triggerAndAwait(SyncTrigger.IMMEDIATE)
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(reportOf(SyncAttempt(SyncTrigger.IMMEDIATE, 0)), result)
        assertEquals(listOf(0L to SyncAttempt(SyncTrigger.IMMEDIATE, 0)), runs.attempts, "the local change's run was replaced")
        assertEquals(listOf(1), runs.waiterCounts)
    }

    /** An immediate run already due, such as a sign-in's, is shared rather than run twice. */
    @Test
    fun `a waited trigger while a sign-in is due joins the sign-in's run`() = schedulerTest { runs, scheduler ->
        scheduler.trigger(SyncTrigger.SIGN_IN)
        val waited = async { scheduler.triggerAndAwait(SyncTrigger.IMMEDIATE) }
        runCurrent()

        assertEquals(reportOf(SyncAttempt(SyncTrigger.SIGN_IN, 0)), waited.await())
        assertEquals(listOf(SyncAttempt(SyncTrigger.SIGN_IN, 0)), runs.attempts.map { it.second })
        assertEquals(listOf(1), runs.waiterCounts)
    }

    /** Calls before the run starts join it, also one that comes while its attempt is due but hasn't started. */
    @Test
    fun `waited triggers before the run starts all join it`() = schedulerTest { runs, scheduler ->
        val first = async { scheduler.triggerAndAwait(SyncTrigger.IMMEDIATE) }
        val second = async { scheduler.triggerAndAwait(SyncTrigger.IMMEDIATE) }
        runCurrent()

        assertEquals(first.await(), second.await())
        assertEquals(listOf(2), runs.waiterCounts, "one run, both waiting for it")
    }

    /** A failed first attempt reports the wait for its retry; the retry has no waiters. */
    @Test
    fun `a waited trigger gets no report from the run's retries`() = schedulerTest { runs, scheduler ->
        runs.outcomes += AttemptOutcome.Failed(retryable = true)

        val result = scheduler.triggerAndAwait(SyncTrigger.IMMEDIATE)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(reportOf(SyncAttempt(SyncTrigger.IMMEDIATE, 0)), result)
        assertEquals(listOf(0, 1), runs.attempts.map { it.second.number })
        assertEquals(listOf(1, 0), runs.waiterCounts)
    }

    @Test
    fun `a cancel tells the waiters of a run that never started sync is unavailable`() = schedulerTest { runs, scheduler ->
        runs.block = CompletableDeferred()
        scheduler.trigger(SyncTrigger.IMMEDIATE)
        runCurrent()
        val waited = async { scheduler.triggerAndAwait(SyncTrigger.IMMEDIATE) }
        runCurrent()

        scheduler.cancelAndJoin()

        assertEquals(SyncNowResult.Unavailable, waited.await())
        assertEquals(1, runs.attempts.size, "the run it waited for never started")
    }

    /** The running attempt's waiters get what it reported as it stopped, or else that sync is unavailable. */
    @Test
    fun `a cancel during the waited run's attempt ends the wait`() = schedulerTest { runs, scheduler ->
        runs.block = CompletableDeferred()
        val waited = async { scheduler.triggerAndAwait(SyncTrigger.IMMEDIATE) }
        runCurrent()

        scheduler.cancelAndJoin()

        assertEquals(SyncNowResult.Unavailable, waited.await())
    }

    @Test
    fun `close ends the wait and later waited triggers return at once`() = schedulerTest { runs, scheduler ->
        runs.block = CompletableDeferred()
        scheduler.trigger(SyncTrigger.IMMEDIATE)
        runCurrent()
        val waited = async { scheduler.triggerAndAwait(SyncTrigger.IMMEDIATE) }
        runCurrent()

        scheduler.close()

        assertEquals(SyncNowResult.Unavailable, waited.await())
        assertEquals(SyncNowResult.Unavailable, scheduler.triggerAndAwait(SyncTrigger.IMMEDIATE))
    }

    @Test
    fun `cancelling the caller stops only the wait`() = schedulerTest { runs, scheduler ->
        runs.block = CompletableDeferred()
        val waited = launch { scheduler.triggerAndAwait(SyncTrigger.IMMEDIATE) }
        runCurrent()

        waited.cancel()
        runCurrent()
        runs.block!!.complete(Unit)
        runs.block = null
        advanceTimeBy(30.minutes.inWholeMilliseconds + 1)
        runCurrent()

        assertEquals(listOf(SyncTrigger.IMMEDIATE, SyncTrigger.PERIODIC), runs.attempts.map { it.second.trigger }, "the run ended and sync went on")
    }

    /** A sync now while the attempt fails and would retry runs right after it, in the retry's place, and is waited for. */
    @Test
    fun `a waited trigger during an attempt that fails and would retry gets the run after it`() = schedulerTest { runs, scheduler ->
        runs.block = CompletableDeferred()
        runs.outcomes += AttemptOutcome.Failed(retryable = true)
        scheduler.trigger(SyncTrigger.LOCAL_CHANGE)
        advanceTimeBy(5_001)
        runCurrent()
        val waited = async { scheduler.triggerAndAwait(SyncTrigger.IMMEDIATE) }
        runCurrent()

        runs.block!!.complete(Unit)
        runs.block = null
        runCurrent()

        assertEquals(reportOf(SyncAttempt(SyncTrigger.IMMEDIATE, 0)), waited.await())
        assertEquals(listOf(SyncAttempt(SyncTrigger.LOCAL_CHANGE, 0), SyncAttempt(SyncTrigger.IMMEDIATE, 0)), runs.attempts.map { it.second }, "no retry")
        assertEquals(listOf(0, 1), runs.waiterCounts)
    }

    /** An attempt that throws doesn't stop the scheduler: its caller gets an answer, and later runs still come. */
    @Test
    fun `an attempt that throws ends its wait and sync goes on`() = schedulerTest { runs, scheduler ->
        runs.throwing = IllegalStateException("a bug in the attempt")

        assertEquals(SyncNowResult.Unavailable, scheduler.triggerAndAwait(SyncTrigger.IMMEDIATE))
        val next = withTimeout(1.minutes) { scheduler.triggerAndAwait(SyncTrigger.IMMEDIATE) }

        assertEquals(reportOf(SyncAttempt(SyncTrigger.IMMEDIATE, 0)), next, "a real run")
        advanceTimeBy(30.minutes.inWholeMilliseconds + 1)
        runCurrent()
        assertEquals(SyncAttempt(SyncTrigger.PERIODIC, 0), runs.attempts.last().second, "the periodic sync still comes")
    }

    /** A cancellation the attempt raised itself, while the scheduler goes on, ends the attempt like any other end. */
    @Test
    fun `an attempt that stops with a stray cancellation ends its wait and sync goes on`() = schedulerTest { runs, scheduler ->
        runs.throwing = CancellationException("stray")

        assertEquals(SyncNowResult.Unavailable, scheduler.triggerAndAwait(SyncTrigger.IMMEDIATE))
        val next = withTimeout(1.minutes) { scheduler.triggerAndAwait(SyncTrigger.IMMEDIATE) }

        assertEquals(reportOf(SyncAttempt(SyncTrigger.IMMEDIATE, 0)), next, "a real run")
        advanceTimeBy(30.minutes.inWholeMilliseconds + 1)
        runCurrent()
        assertEquals(SyncAttempt(SyncTrigger.PERIODIC, 0), runs.attempts.last().second, "the periodic sync still comes")
    }

    @Test
    fun `only an immediate trigger can be waited for`() = schedulerTest { _, scheduler ->
        assertFailsWith<IllegalArgumentException> { scheduler.triggerAndAwait(SyncTrigger.LOCAL_CHANGE) }
    }

    @Test
    fun `close stops the command loop for good`() = schedulerTest { runs, scheduler ->
        runs.block = CompletableDeferred()
        scheduler.trigger(SyncTrigger.IMMEDIATE)
        runCurrent()

        scheduler.close()
        scheduler.trigger(SyncTrigger.IMMEDIATE)
        scheduler.trigger(SyncTrigger.LOCAL_CHANGE)
        advanceTimeBy(60.minutes.inWholeMilliseconds)
        runCurrent()

        assertEquals(1, runs.attempts.size, "nothing runs after close")
        scheduler.cancelAndJoin()
    }
}

/**
 * What each attempt reports to its waiters in these tests: a run whose trigger is the attempt's (its ordinal in
 * `pushed`, so every scheduler trigger reads apart) and whose attempt count is the attempt's number.
 */
private fun reportOf(attempt: SyncAttempt): SyncNowResult = SyncNowResult.Ended(
    SyncRun(
        startedAt = Instant.fromEpochMilliseconds(0).toPlatform(), endedAt = Instant.fromEpochMilliseconds(0).toPlatform(),
        trigger = SyncRunTrigger.MANUAL, result = SyncRunResult.SUCCEEDED, attempts = attempt.number + 1,
        pushed = attempt.trigger.ordinal, pulled = 0, held = 0, error = null
    )
)
