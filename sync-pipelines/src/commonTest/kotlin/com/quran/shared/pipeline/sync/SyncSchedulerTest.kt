@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.quran.shared.pipeline.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

        suspend fun run(attempt: SyncAttempt, retryDelay: (AttemptOutcome.Failed) -> Duration?): AttemptOutcome {
            attempts += scope.testScheduler.currentTime to attempt
            block?.await()
            val outcome = outcomes.removeFirstOrNull() ?: AttemptOutcome.Done
            if (outcome is AttemptOutcome.Failed) {
                val delay = retryDelay(outcome)
                if (delay == null) finalFailures += scope.testScheduler.currentTime else retryDelays += delay
            }
            return outcome
        }
    }

    /** Runs [test] with a scheduler that is cancelled afterwards: its periodic runs would otherwise never end. */
    private fun schedulerTest(
        timings: SchedulerTimings = this.timings,
        random: () -> Double = { 0.5 },
        test: suspend TestScope.(Runs, SyncScheduler) -> Unit
    ): TestResult = runTest {
        val runs = Runs(this)
        val scheduler = SyncScheduler(timings, StandardTestDispatcher(testScheduler), { testScheduler.currentTime }, runs::run, random)
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
    fun `production retries wait about 2, 6, 18 and 54 s, jittered by half and capped at a minute`() {
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
        runs.outcomes += AttemptOutcome.Failed(retryable = true, retryAfterMillis = 6 * 60 * 60 * 1000L)

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
