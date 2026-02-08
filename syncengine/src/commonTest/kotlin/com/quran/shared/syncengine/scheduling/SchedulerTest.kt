@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.quran.shared.syncengine.scheduling

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SchedulerTest {

    companion object {
        private val DEFAULT_TIMEOUT = 6.seconds

        private val STANDARD_TEST_TIMINGS = SchedulerTimings(
            appRefreshInterval = 900.milliseconds,
            standardInterval = 1350.milliseconds,
            localDataModifiedInterval = 450.milliseconds,
            failureRetryingConfig = FailureRetryingConfig(baseDelay = 450.milliseconds, multiplier = 2.5, maximumRetries = 3)
        )

        private val OVERLAP_TEST_TIMINGS = SchedulerTimings(
            appRefreshInterval = 900.milliseconds,
            standardInterval = 2250.milliseconds,
            localDataModifiedInterval = 450.milliseconds,
            failureRetryingConfig = FailureRetryingConfig(baseDelay = 450.milliseconds, multiplier = 2.5, maximumRetries = 3)
        )

        private val SINGLE_RETRY_TIMINGS = SchedulerTimings(
            appRefreshInterval = 900.milliseconds,
            standardInterval = 1350.milliseconds,
            localDataModifiedInterval = 600.milliseconds,
            failureRetryingConfig = FailureRetryingConfig(baseDelay = 300.milliseconds, multiplier = 2.5, maximumRetries = 1)
        )
    }

    private fun runTimeoutTest(testBody: suspend TestScope.() -> Unit) =
        runTest(timeout = DEFAULT_TIMEOUT) { testBody() }

    private fun TestScope.makeScheduler(
        timings: SchedulerTimings,
        taskFunction: suspend () -> Unit,
        reachedMaximumFailureRetries: suspend (Exception) -> Unit = { _ -> },
    ): Scheduler {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return Scheduler(
            timings = timings,
            taskFunction = taskFunction,
            reachedMaximumFailureRetries = reachedMaximumFailureRetries,
            dispatcher = dispatcher,
            timeProviderMs = { testScheduler.currentTime },
        )
    }

    private fun TestScope.runNow() {
        testScheduler.runCurrent()
    }

    private fun TestScope.advanceBy(duration: Duration) {
        testScheduler.advanceTimeBy(duration.inWholeMilliseconds)
        testScheduler.runCurrent()
    }

    private fun expectedRetryTotalMs(config: FailureRetryingConfig): Long {
        var total = 0L
        for (count in 0 until config.maximumRetries) {
            total += (config.baseDelay * config.multiplier.pow(count)).inWholeMilliseconds
        }
        return total
    }

    @Test
    fun `basic trigger timing and standard interval scheduling`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        val firstCallAt = CompletableDeferred<Long>()
        val secondCallAt = CompletableDeferred<Long>()
        var count = 0

        val scheduler = makeScheduler(timings, {
            count++
            when (count) {
                1 -> firstCallAt.complete(testScheduler.currentTime)
                2 -> secondCallAt.complete(testScheduler.currentTime)
            }
        })

        scheduler.invoke(Trigger.APP_REFRESH)
        runNow()

        advanceBy(timings.appRefreshInterval)
        assertEquals(timings.appRefreshInterval.inWholeMilliseconds, firstCallAt.await(), "First call timing")

        advanceBy(timings.standardInterval)
        assertEquals(
            (timings.appRefreshInterval + timings.standardInterval).inWholeMilliseconds,
            secondCallAt.await(),
            "Standard interval scheduling"
        )

        assertEquals(2, count, "Should be called twice")
        scheduler.stop()
        runNow()
    }

    @Test
    fun `LOCAL_DATA_MODIFIED trigger should use faster interval than APP_START`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        val taskCalledAt = CompletableDeferred<Long>()
        var callCount = 0

        val scheduler = makeScheduler(timings, {
            callCount++
            taskCalledAt.complete(testScheduler.currentTime)
        })

        scheduler.invoke(Trigger.APP_REFRESH)
        runNow()

        advanceBy(100.milliseconds)
        val triggerAt = testScheduler.currentTime
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)
        runNow()

        advanceBy(timings.localDataModifiedInterval)
        val calledAt = taskCalledAt.await()
        assertEquals(
            timings.localDataModifiedInterval.inWholeMilliseconds,
            (calledAt - triggerAt),
            "Task should be called with LOCAL_DATA_MODIFIED timing"
        )

        assertEquals(1, callCount, "Should be called once")
        scheduler.stop()
        runNow()
    }

    @Test
    fun `LOCAL_DATA_MODIFIED as first trigger should schedule with localDataModifiedInterval then standard interval`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        val firstCallAt = CompletableDeferred<Long>()
        val secondCallAt = CompletableDeferred<Long>()
        var callCount = 0

        val scheduler = makeScheduler(timings, {
            callCount++
            when (callCount) {
                1 -> firstCallAt.complete(testScheduler.currentTime)
                2 -> secondCallAt.complete(testScheduler.currentTime)
            }
        })

        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)
        runNow()

        advanceBy(timings.localDataModifiedInterval)
        assertEquals(
            timings.localDataModifiedInterval.inWholeMilliseconds,
            firstCallAt.await(),
            "First call should use LOCAL_DATA_MODIFIED timing"
        )
        assertEquals(1, callCount, "Should be called once for first call")

        advanceBy(timings.standardInterval)
        assertEquals(
            (timings.localDataModifiedInterval + timings.standardInterval).inWholeMilliseconds,
            secondCallAt.await(),
            "Second call should use standard interval timing"
        )
        assertEquals(2, callCount, "Should be called twice total")

        scheduler.stop()
        runNow()
    }

    @Test
    fun `trigger during standard delay should cancel and reschedule since it should fire quicker`() = runTimeoutTest {
        val timings = OVERLAP_TEST_TIMINGS
        val firstCallAt = CompletableDeferred<Long>()
        val secondCallAt = CompletableDeferred<Long>()
        var callCount = 0

        val scheduler = makeScheduler(timings, {
            callCount++
            when (callCount) {
                1 -> firstCallAt.complete(testScheduler.currentTime)
                2 -> secondCallAt.complete(testScheduler.currentTime)
            }
        })

        scheduler.invoke(Trigger.APP_REFRESH)
        runNow()

        advanceBy(timings.appRefreshInterval)
        assertEquals(
            timings.appRefreshInterval.inWholeMilliseconds,
            firstCallAt.await(),
            "First call should use APP_START timing"
        )
        assertEquals(1, callCount, "Should be called once for first call")

        advanceBy(50.milliseconds)
        val triggerAt = testScheduler.currentTime
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)
        runNow()

        advanceBy(timings.localDataModifiedInterval)
        val calledAt = secondCallAt.await()
        assertEquals(
            timings.localDataModifiedInterval.inWholeMilliseconds,
            (calledAt - triggerAt),
            "Second call should use LOCAL_DATA_MODIFIED timing, not standard interval timing"
        )
        assertEquals(2, callCount, "Should be called twice total")

        scheduler.stop()
        runNow()
    }

    @Test
    fun `IMMEDIATE trigger should fire immediately`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        val taskCompletedAt = CompletableDeferred<Long>()

        val scheduler = makeScheduler(timings, {
            taskCompletedAt.complete(testScheduler.currentTime)
        })

        scheduler.invoke(Trigger.IMMEDIATE)
        runNow()
        runNow() // run the zero-delay job

        assertEquals(0L, taskCompletedAt.await(), "IMMEDIATE trigger should fire immediately")
        scheduler.stop()
        runNow()
    }

    @Test
    fun `task function failures should retry with exponential backoff until maximum retries reached`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        var callCount = 0
        var maxRetriesError: Exception? = null
        val maxRetriesDeferred = CompletableDeferred<Unit>()

        val scheduler = makeScheduler(
            timings = timings,
            taskFunction = {
                callCount++
                throw Exception("Test failure")
            },
            reachedMaximumFailureRetries = { error ->
                maxRetriesError = error
                maxRetriesDeferred.complete(Unit)
            }
        )

        scheduler.invoke(Trigger.IMMEDIATE)
        runNow()
        testScheduler.advanceUntilIdle()

        maxRetriesDeferred.await()

        assertEquals(
            timings.failureRetryingConfig.maximumRetries + 1,
            callCount,
            "Should be called maximum retries + 1 times (initial + retries)"
        )
        assertEquals("Test failure", maxRetriesError?.message, "Exception should be passed to max retries callback")
        assertEquals(
            expectedRetryTotalMs(timings.failureRetryingConfig),
            testScheduler.currentTime,
            "Total virtual time should match retry delays"
        )

        scheduler.stop()
        runNow()
    }

    @Test
    fun `after task function failures standard interval scheduling should not occur`() = runTimeoutTest {
        val timings = SINGLE_RETRY_TIMINGS
        var callCount = 0
        val maxRetriesDeferred = CompletableDeferred<Unit>()

        val scheduler = makeScheduler(
            timings = timings,
            taskFunction = {
                callCount++
                throw Exception("Test failure")
            },
            reachedMaximumFailureRetries = { _ ->
                maxRetriesDeferred.complete(Unit)
            }
        )

        scheduler.invoke(Trigger.IMMEDIATE)
        runNow()
        testScheduler.advanceUntilIdle()

        maxRetriesDeferred.await()

        // If a standard interval was scheduled, it would cause an additional call after this advance.
        advanceBy(timings.standardInterval + 50.milliseconds)
        assertEquals(
            timings.failureRetryingConfig.maximumRetries + 1,
            callCount,
            "Call count should remain at maximum retries + 1, no additional calls should be scheduled"
        )

        scheduler.stop()
        runNow()
    }

    @Test
    fun `after maximum retries reached applying any trigger should fire normally`() = runTimeoutTest {
        val timings = SINGLE_RETRY_TIMINGS
        var callCount = 0
        var shouldSucceed = false
        val maxRetriesDeferred = CompletableDeferred<Unit>()
        val newTaskDeferred = CompletableDeferred<Unit>()

        val scheduler = makeScheduler(
            timings = timings,
            taskFunction = {
                callCount++
                if (shouldSucceed) {
                    newTaskDeferred.complete(Unit)
                } else {
                    throw Exception("Test failure")
                }
            },
            reachedMaximumFailureRetries = { _ ->
                maxRetriesDeferred.complete(Unit)
            }
        )

        scheduler.invoke(Trigger.IMMEDIATE)
        runNow()
        testScheduler.advanceUntilIdle()

        maxRetriesDeferred.await()
        assertEquals(
            timings.failureRetryingConfig.maximumRetries + 1,
            callCount,
            "Should be called maximum retries + 1 times before max retries reached"
        )

        shouldSucceed = true
        val triggerAt = testScheduler.currentTime
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)
        runNow()

        advanceBy(timings.localDataModifiedInterval)
        newTaskDeferred.await()

        assertEquals(
            timings.failureRetryingConfig.maximumRetries + 2,
            callCount,
            "Should be called one more time after applying new trigger"
        )
        assertEquals(
            timings.localDataModifiedInterval.inWholeMilliseconds,
            (testScheduler.currentTime - triggerAt),
            "New trigger should use LOCAL_DATA_MODIFIED timing after maximum retries reached"
        )

        scheduler.stop()
        runNow()
    }

    @Test
    fun `triggers during job execution are handled correctly`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        val taskStarted = CompletableDeferred<Unit>()
        val taskCanProceed = CompletableDeferred<Unit>()
        val secondTaskCompletedAt = CompletableDeferred<Long>()
        var callCount = 0

        val scheduler = makeScheduler(timings, {
            callCount++
            if (callCount == 1) {
                taskStarted.complete(Unit)
                taskCanProceed.await()
            } else if (callCount == 2) {
                secondTaskCompletedAt.complete(testScheduler.currentTime)
            }
        })

        scheduler.invoke(Trigger.APP_REFRESH)
        runNow()
        advanceBy(timings.appRefreshInterval) // start first task

        taskStarted.await()

        val triggerAt = testScheduler.currentTime
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)
        runNow()

        taskCanProceed.complete(Unit)
        runNow()

        advanceBy(timings.localDataModifiedInterval)
        val calledAt = secondTaskCompletedAt.await()

        assertEquals(
            timings.localDataModifiedInterval.inWholeMilliseconds,
            (calledAt - triggerAt),
            "Buffered LOCAL_DATA_MODIFIED should be used"
        )
        assertEquals(2, callCount)

        scheduler.stop()
        runNow()
    }

    @Test
    fun `triggers during failed jobs should be ignored`() = runTimeoutTest {
        val timings = SINGLE_RETRY_TIMINGS
        val maxRetriesReached = CompletableDeferred<Unit>()
        val callTimes = mutableListOf<Long>()
        var callCount = 0

        val scheduler = makeScheduler(
            timings = timings,
            taskFunction = {
                callCount++
                callTimes.add(testScheduler.currentTime)
                throw Exception("Simulated failure")
            },
            reachedMaximumFailureRetries = { _ ->
                maxRetriesReached.complete(Unit)
            }
        )

        // First call at t=0, then retry at baseDelay. During the retry delay, new triggers should be ignored.
        scheduler.invoke(Trigger.IMMEDIATE)
        runNow()
        runNow()

        advanceBy(100.milliseconds)
        scheduler.invoke(Trigger.IMMEDIATE)
        runNow()

        advanceBy(timings.failureRetryingConfig.baseDelay - 100.milliseconds)
        maxRetriesReached.await()

        assertEquals(2, callCount, "Only initial + retry should run for SINGLE_RETRY_TIMINGS")
        assertEquals(
            listOf(0L, timings.failureRetryingConfig.baseDelay.inWholeMilliseconds),
            callTimes,
            "Retry schedule should not be affected by triggers during retry delay"
        )

        scheduler.stop()
        runNow()
    }

    @Test
    fun `triggers should be ignored after scheduler is stopped`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        val taskCompleted = CompletableDeferred<Unit>()
        var callCount = 0

        val scheduler = makeScheduler(timings, {
            callCount++
            taskCompleted.complete(Unit)
        })

        scheduler.stop()
        runNow()

        scheduler.invoke(Trigger.APP_REFRESH)
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)
        scheduler.invoke(Trigger.IMMEDIATE)
        runNow()

        advanceBy(timings.appRefreshInterval + 200.milliseconds)

        assertEquals(0, callCount, "Task should not be called after scheduler is stopped")
        assertTrue(taskCompleted.isCompleted.not(), "Task completion should not be triggered after stop")
    }
}
