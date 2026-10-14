@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.quran.shared.pipeline.sync

import com.quran.shared.syncengine.scheduling.FailureRetryingConfig
import com.quran.shared.syncengine.scheduling.SchedulerTimings
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** [SyncScheduler] on virtual time: when runs start, how failures are retried, and that sync never stops (S21). */
class SyncSchedulerTest {
    private val timings = SchedulerTimings(
        standardInterval = 30.minutes,
        appRefreshInterval = 30.seconds,
        localDataModifiedInterval = 5.seconds,
        failureRetryingConfig = FailureRetryingConfig(baseDelay = 200.milliseconds, multiplier = 2.5, maximumRetries = 5)
    )

    /** Records each attempt (with the time it started) and answers it with the next scripted outcome. */
    private class Runs(private val scope: TestScope) {
        val attempts = mutableListOf<Pair<Long, SyncAttempt>>()
        val finalFailures = mutableListOf<Long>()
        val outcomes = ArrayDeque<AttemptOutcome>()
        var block: CompletableDeferred<Unit>? = null

        suspend fun run(attempt: SyncAttempt, willRetry: (AttemptOutcome.Failed) -> Boolean): AttemptOutcome {
            attempts += scope.testScheduler.currentTime to attempt
            block?.await()
            val outcome = outcomes.removeFirstOrNull() ?: AttemptOutcome.Done
            if (outcome is AttemptOutcome.Failed && !willRetry(outcome)) finalFailures += scope.testScheduler.currentTime
            return outcome
        }
    }

    /** Runs [test] with a scheduler that is cancelled afterwards: its periodic runs would otherwise never end. */
    private fun schedulerTest(test: suspend TestScope.(Runs, SyncScheduler) -> Unit): TestResult = runTest {
        val runs = Runs(this)
        val scheduler = SyncScheduler(timings, StandardTestDispatcher(testScheduler), { testScheduler.currentTime }, runs::run)
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

        advanceTimeBy(30.minutes.inWholeMilliseconds)
        runCurrent()
        assertEquals(SyncAttempt(SyncTrigger.PERIODIC, 0), runs.attempts.last().second, "S21: sync goes on")
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
    fun `a local change during a run runs again after it while an immediate trigger during a run is dropped`() = schedulerTest { runs, scheduler ->
        runs.block = CompletableDeferred()

        scheduler.trigger(SyncTrigger.IMMEDIATE)
        runCurrent()
        scheduler.trigger(SyncTrigger.IMMEDIATE)
        scheduler.trigger(SyncTrigger.LOCAL_CHANGE)
        runCurrent()
        runs.block!!.complete(Unit)
        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(listOf(SyncTrigger.IMMEDIATE, SyncTrigger.LOCAL_CHANGE), runs.attempts.map { it.second.trigger })
        assertEquals(5_000L, runs.attempts.last().first, "after the local change delay, counted from the run's end")
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
}
