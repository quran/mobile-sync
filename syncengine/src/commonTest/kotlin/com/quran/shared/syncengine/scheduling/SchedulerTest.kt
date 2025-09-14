package com.quran.shared.syncengine.scheduling

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

class SchedulerTest {

    companion object {
        private val TIMING_TOLERANCE = 300.milliseconds
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
        
        @OptIn(ExperimentalTime::class)
        private fun currentTimeMs(): Long = Clock.System.now().toEpochMilliseconds()
    }

    private fun runTimeoutTest(testBody: suspend TestScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(DEFAULT_TIMEOUT) {
                testBody()
            }
        }
    }


    @Test
    fun `basic trigger timing and standard interval scheduling`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        var taskCompleted = CompletableDeferred<Long>()
        var count = 0

        val scheduler = Scheduler(timings, {
            count++
            taskCompleted.complete(currentTimeMs())
        }, { _ -> })

        val timeBeforeCall = currentTimeMs()
        scheduler.invoke(Trigger.APP_REFRESH)

        val firstCallTime = taskCompleted.await()
        val firstDelay = (firstCallTime - timeBeforeCall).milliseconds
        assertTimingWithinTolerance(firstDelay, timings.appRefreshInterval, "First call timing")
        
        taskCompleted = CompletableDeferred()
        val secondCallTime = taskCompleted.await()
        val totalTime = (secondCallTime - timeBeforeCall).milliseconds
        val expectedTotalTime = timings.appRefreshInterval + timings.standardInterval
        assertTimingWithinTolerance(totalTime, expectedTotalTime, "Standard interval scheduling")
        
        assertEquals(2, count, "Should be called twice")
        scheduler.stop()
    }

    @Test
    fun `LOCAL_DATA_MODIFIED trigger should use faster interval than APP_START`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        val taskCompleted = CompletableDeferred<Long>()
        var callCount = 0

        val scheduler = Scheduler(timings, {
            callCount++
            taskCompleted.complete(currentTimeMs())
        }, { _ -> })

        scheduler.invoke(Trigger.APP_REFRESH)
        
        delay(100.milliseconds)
        val timeBeforeDataModifiedTrigger = currentTimeMs()
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)

        val timeAfterCall = taskCompleted.await()

        val actualDelay = (timeAfterCall - timeBeforeDataModifiedTrigger).milliseconds
        val expectedDelay = timings.localDataModifiedInterval

        assertTimingWithinTolerance(
            actualDelay,
            expectedDelay,
            "Task should be called with LOCAL_DATA_MODIFIED timing"
        )
        
        assertEquals(1, callCount, "Should be called once")
        scheduler.stop()
    }

    @Test
    fun `LOCAL_DATA_MODIFIED as first trigger should schedule with localDataModifiedInterval then standard interval`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        var taskCompleted = CompletableDeferred<Long>()
        var callCount = 0

        val scheduler = Scheduler(timings, {
            callCount++
            taskCompleted.complete(currentTimeMs())
        }, { _ -> })

        val timeBeforeFirstTrigger = currentTimeMs()
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)

        val firstCallTime = taskCompleted.await()
        val firstCallDelay = (firstCallTime - timeBeforeFirstTrigger).milliseconds
        val expectedFirstCallDelay = timings.localDataModifiedInterval

        assertTimingWithinTolerance(
            firstCallDelay,
            expectedFirstCallDelay,
            "First call should use LOCAL_DATA_MODIFIED timing"
        )
        assertEquals(1, callCount, "Should be called once for first call")

        taskCompleted = CompletableDeferred()
        val timeBeforeSecondCall = currentTimeMs()
        val secondCallTime = taskCompleted.await()
        val secondCallDelay = (secondCallTime - timeBeforeSecondCall).milliseconds
        val expectedSecondCallDelay = timings.standardInterval

        assertTimingWithinTolerance(
            secondCallDelay,
            expectedSecondCallDelay,
            "Second call should use standard interval timing"
        )
        assertEquals(2, callCount, "Should be called twice total")
        
        scheduler.stop()
    }

    @Test
    fun `trigger during standard delay should cancel and reschedule since it should fire quicker`() = runTimeoutTest {
        val timings = OVERLAP_TEST_TIMINGS
        var taskCompleted = CompletableDeferred<Long>()
        var callCount = 0

        val scheduler = Scheduler(timings, {
            callCount++
            taskCompleted.complete(currentTimeMs())
        }, { _ -> })

        val timeBeforeAppStartTrigger = currentTimeMs()
        scheduler.invoke(Trigger.APP_REFRESH)

        val firstCallTime = taskCompleted.await()
        val firstCallDelay = (firstCallTime - timeBeforeAppStartTrigger).milliseconds
        val expectedFirstCallDelay = timings.appRefreshInterval

        assertTimingWithinTolerance(
            firstCallDelay,
            expectedFirstCallDelay,
            "First call should use APP_START timing"
        )
        assertEquals(1, callCount, "Should be called once for first call")

        delay(50.milliseconds)
        val timeBeforeLocalDataTrigger = currentTimeMs()
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)

        taskCompleted = CompletableDeferred()
        val secondCallTime = taskCompleted.await()
        val secondCallDelay = (secondCallTime - timeBeforeLocalDataTrigger).milliseconds
        val expectedSecondCallDelay = timings.localDataModifiedInterval

        assertTimingWithinTolerance(
            secondCallDelay,
            expectedSecondCallDelay,
            "Second call should use LOCAL_DATA_MODIFIED timing, not standard interval timing"
        )
        assertEquals(2, callCount, "Should be called twice total")
        
        scheduler.stop()
    }

    @Test
    fun `IMMEDIATE trigger should fire immediately`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        val taskCompleted = CompletableDeferred<Long>()

        val scheduler = Scheduler(timings, {
            taskCompleted.complete(currentTimeMs())
        }, { _ -> })

        val timeBeforeCall = currentTimeMs()
        scheduler.invoke(Trigger.IMMEDIATE)
        val callTime = taskCompleted.await()
        
        assertTrue(
            (callTime - timeBeforeCall).milliseconds < TIMING_TOLERANCE,
            "IMMEDIATE trigger should fire immediately"
        )
        scheduler.stop()
    }

    @Test
    fun `task function failures should retry with exponential backoff until maximum retries reached`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        var callCount = 0
        var maxRetriesError: Exception? = null
        val maxRetriesDeferred = CompletableDeferred<Unit>()

        val scheduler = Scheduler(timings, { 
            callCount++
            throw Exception("Test failure")
        }, { error ->
            maxRetriesError = error
            maxRetriesDeferred.complete(Unit)
        })

        val timeBeforeTrigger = currentTimeMs()
        scheduler.invoke(Trigger.IMMEDIATE)

        maxRetriesDeferred.await()

        assertEquals(timings.failureRetryingConfig.maximumRetries + 1, callCount,
            "Should be called maximum retries + 1 times (initial + retries)")
        assertEquals("Test failure", maxRetriesError?.message, "Exception should be passed to max retries callback")

        val totalTime = (currentTimeMs() - timeBeforeTrigger).milliseconds
        val expectedMinTime = timings.failureRetryingConfig.baseDelay *
            (1 + timings.failureRetryingConfig.multiplier +
             timings.failureRetryingConfig.multiplier * timings.failureRetryingConfig.multiplier)

        assertTrue(totalTime >= expectedMinTime,
            "Total time should account for retry delays. Expected at least $expectedMinTime, got $totalTime")
        
        scheduler.stop()
    }

    @Test
    fun `after task function failures standard interval scheduling should not occur`() = runTimeoutTest {
        val timings = SINGLE_RETRY_TIMINGS
        var callCount = 0
        val maxRetriesDeferred = CompletableDeferred<Unit>()

        val scheduler = Scheduler(timings, { 
            callCount++
            throw Exception("Test failure")
        }, { _ ->
            maxRetriesDeferred.complete(Unit)
        })

        scheduler.invoke(Trigger.IMMEDIATE)

        maxRetriesDeferred.await()

        delay(timings.standardInterval + 50.milliseconds)
        
        assertEquals(timings.failureRetryingConfig.maximumRetries + 1, callCount,
            "Call count should remain at maximum retries + 1, no additional calls should be scheduled")
        
        scheduler.stop()
    }

    @Test
    fun `after maximum retries reached applying any trigger should fire normally`() = runTimeoutTest {
        val timings = SINGLE_RETRY_TIMINGS
        var callCount = 0
        var shouldSucceed = false
        val maxRetriesDeferred = CompletableDeferred<Unit>()
        val newTaskDeferred = CompletableDeferred<Unit>()

        val scheduler = Scheduler(timings, { 
            callCount++
            if (shouldSucceed) {
                newTaskDeferred.complete(Unit)
                } else {
                throw Exception("Test failure")
            }
        }, { _ ->
            maxRetriesDeferred.complete(Unit)
        })

        scheduler.invoke(Trigger.IMMEDIATE)

        maxRetriesDeferred.await()

        assertEquals(timings.failureRetryingConfig.maximumRetries + 1, callCount,
            "Should be called maximum retries + 1 times before max retries reached")

        shouldSucceed = true
        val timeBeforeNewTrigger = currentTimeMs()
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)

        newTaskDeferred.await()

        val timeAfterNewCall = currentTimeMs()
        val newCallDelay = (timeAfterNewCall - timeBeforeNewTrigger).milliseconds
        val expectedDelay = timings.localDataModifiedInterval

        assertEquals(timings.failureRetryingConfig.maximumRetries + 2, callCount,
            "Should be called one more time after applying new trigger")

        assertTimingWithinTolerance(
            newCallDelay,
            expectedDelay,
            "New trigger should use LOCAL_DATA_MODIFIED timing after maximum retries reached"
        )
        
        scheduler.stop()
    }

    @Test
    fun `triggers during job execution are handled correctly`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        val taskStarted = CompletableDeferred<Unit>()
        val taskCanProceed = CompletableDeferred<Unit>()
        val secondTaskCompleted = CompletableDeferred<Long>()
        var callCount = 0

        val scheduler = Scheduler(timings, {
            callCount++
            if (callCount == 1) {
                taskStarted.complete(Unit)
                taskCanProceed.await()
            } else if (callCount == 2) {
                secondTaskCompleted.complete(currentTimeMs())
            }
        }, { _ -> })

        scheduler.invoke(Trigger.APP_REFRESH)
        taskStarted.await()
        
        val timeBeforeTrigger = currentTimeMs()
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)
        taskCanProceed.complete(Unit)
        
        val secondTaskTime = secondTaskCompleted.await()
        val actualDelay = (secondTaskTime - timeBeforeTrigger).milliseconds

        assertTimingWithinTolerance(
            actualDelay,
            timings.localDataModifiedInterval,
            "Buffered LOCAL_DATA_MODIFIED should be used"
        )
        
        assertEquals(2, callCount)
        scheduler.stop()
    }


    @Test
    fun `triggers during failed jobs should be ignored`() = runTimeoutTest {
        val timings = SINGLE_RETRY_TIMINGS
        val taskCanProceed = CompletableDeferred<Unit>()
        val maxRetriesReached = CompletableDeferred<Unit>()
        var callCount = 0

        val scheduler = Scheduler(timings, {
            callCount++
            if (callCount == 1) {
                taskCanProceed.await()
            }
            throw Exception("Simulated failure")
        }, { _ -> 
            maxRetriesReached.complete(Unit)
        })

        scheduler.invoke(Trigger.APP_REFRESH)
        delay(50.milliseconds)
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)
        taskCanProceed.complete(Unit)
        maxRetriesReached.await()
        
        val callCountAfterFailure = callCount
        delay(timings.localDataModifiedInterval + TIMING_TOLERANCE)
        
        assertEquals(callCountAfterFailure, callCount, "No additional tasks should be scheduled")
        scheduler.stop()
    }


    @Test
    fun `triggers should be ignored after scheduler is stopped`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        val taskCompleted = CompletableDeferred<Unit>()
        var callCount = 0

        val scheduler = Scheduler(timings, {
            callCount++
            taskCompleted.complete(Unit)
        }, { _ -> })

        scheduler.stop()
        
        // Give it a moment to process the stop
        delay(50.milliseconds)

        scheduler.invoke(Trigger.APP_REFRESH)
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)
        scheduler.invoke(Trigger.IMMEDIATE)
        
        delay(timings.appRefreshInterval + 200.milliseconds)
        
        assertEquals(0, callCount, "Task should not be called after scheduler is stopped")
        
        assertTrue(taskCompleted.isCompleted.not(), "Task completion should not be triggered after stop")
    }

    private fun assertTimingWithinTolerance(actual: kotlin.time.Duration, expected: kotlin.time.Duration, message: String) {
        val difference = kotlin.math.abs((actual - expected).inWholeMilliseconds)
        assertTrue(
            difference <= TIMING_TOLERANCE.inWholeMilliseconds,
            "$message. Expected: $expected ± $TIMING_TOLERANCE, Actual: $actual (difference: ${difference}ms)"
        )
    }
}
