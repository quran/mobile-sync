package com.quran.shared.syncengine.scheduling

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class SchedulerTest {

    companion object {
        private const val TIMING_TOLERANCE_MS = 100L
        private const val DEFAULT_TIMEOUT_MS = 15_000L
        
        // Predefined timing configurations for different test scenarios
        private val STANDARD_TEST_TIMINGS = SchedulerTimings(
            appStartInterval = 2000L,
            standardInterval = 3000L,
            localDataModifiedInterval = 1000L,
            retryingTimings = RetryingTimings(baseDelay = 200, multiplier = 2.5, maximumRetries = 5)
        )
        
        private val SLOW_OVERRIDE_TIMINGS = SchedulerTimings(
            appStartInterval = 3000L,
            standardInterval = 5000L,
            localDataModifiedInterval = 1000L,
            retryingTimings = RetryingTimings(baseDelay = 200, multiplier = 2.5, maximumRetries = 5)
        )
        
        private val LONG_SCHEDULING_TIMINGS = SchedulerTimings(
            appStartInterval = 30000L,
            standardInterval = 60000L,
            localDataModifiedInterval = 5000L,
            retryingTimings = RetryingTimings(baseDelay = 200, multiplier = 2.5, maximumRetries = 5)
        )
        
        private val OVERLAP_TEST_TIMINGS = SchedulerTimings(
            appStartInterval = 2000L,
            standardInterval = 5000L,
            localDataModifiedInterval = 1000L,
            retryingTimings = RetryingTimings(baseDelay = 200, multiplier = 2.5, maximumRetries = 5)
        )
        
        private val RETRY_TEST_TIMINGS = SchedulerTimings(
            appStartInterval = 2000L,
            standardInterval = 3000L,
            localDataModifiedInterval = 1000L,
            retryingTimings = RetryingTimings(baseDelay = 200, multiplier = 2.5, maximumRetries = 3)
        )
        
        private val SINGLE_RETRY_TIMINGS = SchedulerTimings(
            appStartInterval = 2000L,
            standardInterval = 3000L,
            localDataModifiedInterval = 1000L,
            retryingTimings = RetryingTimings(baseDelay = 200, multiplier = 2.5, maximumRetries = 1)
        )
        
        private fun createScheduler(
            timings: SchedulerTimings,
            taskFunction: suspend () -> Result<Unit>
        ) = Scheduler(timings, taskFunction, { _ -> })
        
        private fun assertTimingWithinTolerance(
            actualDelay: Long,
            expectedDelay: Long,
            message: String
        ) {
            assertTrue(
                kotlin.math.abs(actualDelay - expectedDelay) < TIMING_TOLERANCE_MS,
                "$message Expected ~${expectedDelay}ms, got ${actualDelay}ms"
            )
        }
    }

    private fun runTimeoutTest(testBody: suspend TestScope.() -> Unit) {
        runTest {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(DEFAULT_TIMEOUT_MS) {
                    testBody()
                }
            }
        }
    }

    @Test
    fun `apply with APP_START should schedule task with correct timing`() {
        val timings = LONG_SCHEDULING_TIMINGS
        var taskFunctionCalled = false
        
        val scheduler = createScheduler(timings) {
            taskFunctionCalled = true
            Result.success(Unit)
        }

        scheduler.apply(Trigger.APP_START)
        
        assertFalse(taskFunctionCalled, "Task function should not be called immediately")
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `taskFunction should be called eventually`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        val taskCompleted = CompletableDeferred<Long>()

        val scheduler = createScheduler(timings) {
            val callTime = Clock.System.now().toEpochMilliseconds()
            taskCompleted.complete(callTime)
            Result.success(Unit)
        }

        val timeBeforeCall = Clock.System.now().toEpochMilliseconds()
        scheduler.apply(Trigger.APP_START)

        val timeAfterCall = taskCompleted.await()

        assertTrue(timeAfterCall > timeBeforeCall, "Task must be called after apply")
        
        val actualDelay = timeAfterCall - timeBeforeCall
        val expectedDelay = timings.appStartInterval
        assertTrue(
            actualDelay - expectedDelay < TIMING_TOLERANCE_MS,
            "Task should be called quickly with no delay, took ${actualDelay}ms while expected is ${timings.appStartInterval}ms"
        )
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `scheduler should keep firing with same interval`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        var taskCompleted = CompletableDeferred<Int>()

        var count = 0
        val timeBeforeTest = Clock.System.now().toEpochMilliseconds()
        val scheduler = createScheduler(timings) {
            count++
            taskCompleted.complete(count)
            Result.success(Unit)
        }

        scheduler.apply(Trigger.APP_START)

        taskCompleted.await()
        taskCompleted = CompletableDeferred()
        val returnedCount = taskCompleted.await()
        val timeAfterTest = Clock.System.now().toEpochMilliseconds()

        assertEquals(2, returnedCount, "Expected to be called twice.")
        
        val timeDelay = timeAfterTest - timeBeforeTest
        val expectedTotalDelay = timings.appStartInterval + timings.standardInterval
        assertTrue(
            timeDelay - expectedTotalDelay < TIMING_TOLERANCE_MS,
            "Expected to wait the appStartInterval for first call and standardInterval for second call. Time delay: $timeDelay"
        )
        
        scheduler.stop()
    }

    @Test
    fun `scheduler should stop when stop is called`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        var taskCompleted = CompletableDeferred<Int>()
        var callCount = 0

        val scheduler = createScheduler(timings) {
            callCount++
            taskCompleted.complete(callCount)
            Result.success(Unit)
        }

        scheduler.apply(Trigger.APP_START)

        taskCompleted.await()
        taskCompleted = CompletableDeferred()
        taskCompleted.await()

        scheduler.stop()

        val waiting = CompletableDeferred<Unit>()
        backgroundScope.launch {
            delay(1000)
            waiting.complete(Unit)
        }
        waiting.await()

        assertEquals(2, callCount, "Expect only two calls before stop is called.")
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `LOCAL_DATA_MODIFIED trigger should use faster interval than APP_START`() = runTimeoutTest {
        val timings = SLOW_OVERRIDE_TIMINGS
        val taskCompleted = CompletableDeferred<Long>()
        var callCount = 0

        val scheduler = createScheduler(timings) {
            callCount++
            val callTime = Clock.System.now().toEpochMilliseconds()
            taskCompleted.complete(callTime)
            Result.success(Unit)
        }

        scheduler.apply(Trigger.APP_START)
        
        delay(500)
        val timeBeforeDataModifiedTrigger = Clock.System.now().toEpochMilliseconds()
        scheduler.apply(Trigger.LOCAL_DATA_MODIFIED)

        val timeAfterCall = taskCompleted.await()

        val actualDelay = timeAfterCall - timeBeforeDataModifiedTrigger
        val expectedDelay = timings.localDataModifiedInterval
        
        assertTimingWithinTolerance(
            actualDelay,
            expectedDelay,
            "Task should be called with LOCAL_DATA_MODIFIED timing"
        )
        
        assertEquals(1, callCount, "Should be called once")
        scheduler.stop()
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `after LOCAL_DATA_MODIFIED trigger subsequent calls should use standard interval`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        var taskCompleted = CompletableDeferred<Long>()
        var callCount = 0

        val scheduler = createScheduler(timings) {
            callCount++
            val callTime = Clock.System.now().toEpochMilliseconds()
            taskCompleted.complete(callTime)
            Result.success(Unit)
        }

        scheduler.apply(Trigger.APP_START)
        
        delay(500)
        val timeBeforeDataModifiedTrigger = Clock.System.now().toEpochMilliseconds()
        scheduler.apply(Trigger.LOCAL_DATA_MODIFIED)

        val firstCallTime = taskCompleted.await()
        val firstCallDelay = firstCallTime - timeBeforeDataModifiedTrigger
        val expectedFirstCallDelay = timings.localDataModifiedInterval
        
        assertTimingWithinTolerance(
            firstCallDelay,
            expectedFirstCallDelay,
            "First call should use LOCAL_DATA_MODIFIED timing"
        )
        assertEquals(1, callCount, "Should be called once for first call")

        taskCompleted = CompletableDeferred()
        val timeBeforeSecondCall = Clock.System.now().toEpochMilliseconds()
        val secondCallTime = taskCompleted.await()
        val secondCallDelay = secondCallTime - timeBeforeSecondCall
        val expectedSecondCallDelay = timings.standardInterval
        
        assertTimingWithinTolerance(
            secondCallDelay,
            expectedSecondCallDelay,
            "Second call should use standard interval timing"
        )
        assertEquals(2, callCount, "Should be called twice total")
        
        scheduler.stop()
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `LOCAL_DATA_MODIFIED as first trigger should schedule with localDataModifiedInterval then standard interval`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        var taskCompleted = CompletableDeferred<Long>()
        var callCount = 0

        val scheduler = createScheduler(timings) {
            callCount++
            val callTime = Clock.System.now().toEpochMilliseconds()
            taskCompleted.complete(callTime)
            Result.success(Unit)
        }

        val timeBeforeFirstTrigger = Clock.System.now().toEpochMilliseconds()
        scheduler.apply(Trigger.LOCAL_DATA_MODIFIED)

        val firstCallTime = taskCompleted.await()
        val firstCallDelay = firstCallTime - timeBeforeFirstTrigger
        val expectedFirstCallDelay = timings.localDataModifiedInterval
        
        assertTimingWithinTolerance(
            firstCallDelay,
            expectedFirstCallDelay,
            "First call should use LOCAL_DATA_MODIFIED timing"
        )
        assertEquals(1, callCount, "Should be called once for first call")

        taskCompleted = CompletableDeferred()
        val timeBeforeSecondCall = Clock.System.now().toEpochMilliseconds()
        val secondCallTime = taskCompleted.await()
        val secondCallDelay = secondCallTime - timeBeforeSecondCall
        val expectedSecondCallDelay = timings.standardInterval
        
        assertTimingWithinTolerance(
            secondCallDelay,
            expectedSecondCallDelay,
            "Second call should use standard interval timing"
        )
        assertEquals(2, callCount, "Should be called twice total")
        
        scheduler.stop()
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `APP_START trigger before previous LOCAL_DATA_MODIFIED is delivered should be ignored`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        var taskCompleted = CompletableDeferred<Long>()
        var callCount = 0

        val scheduler = createScheduler(timings) {
            callCount++
            val callTime = Clock.System.now().toEpochMilliseconds()
            taskCompleted.complete(callTime)
            Result.success(Unit)
        }

        val timeBeforeLocalDataTrigger = Clock.System.now().toEpochMilliseconds()
        scheduler.apply(Trigger.LOCAL_DATA_MODIFIED)

        delay(100)
        scheduler.apply(Trigger.APP_START)

        val firstCallTime = taskCompleted.await()
        val firstCallDelay = firstCallTime - timeBeforeLocalDataTrigger
        val expectedFirstCallDelay = timings.localDataModifiedInterval
        
        assertTimingWithinTolerance(
            firstCallDelay,
            expectedFirstCallDelay,
            "First call should use LOCAL_DATA_MODIFIED timing, not APP_START timing"
        )
        assertEquals(1, callCount, "Should be called once for first call")

        val timeBeforeSecondCall = Clock.System.now().toEpochMilliseconds()
        taskCompleted = CompletableDeferred()
        val secondCallTime = taskCompleted.await()
        val secondCallDelay = secondCallTime - timeBeforeSecondCall
        val expectedSecondCallDelay = timings.standardInterval
        
        assertTimingWithinTolerance(
            secondCallDelay,
            expectedSecondCallDelay,
            "Second call should use standard interval timing"
        )
        assertEquals(2, callCount, "Should be called twice total")
        
        scheduler.stop()
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `trigger during standard delay should cancel and reschedule`() = runTimeoutTest {
        val timings = OVERLAP_TEST_TIMINGS
        var taskCompleted = CompletableDeferred<Long>()
        var callCount = 0

        val scheduler = createScheduler(timings) {
            callCount++
            val callTime = Clock.System.now().toEpochMilliseconds()
            taskCompleted.complete(callTime)
            Result.success(Unit)
        }

        val timeBeforeAppStartTrigger = Clock.System.now().toEpochMilliseconds()
        scheduler.apply(Trigger.APP_START)

        val firstCallTime = taskCompleted.await()
        val firstCallDelay = firstCallTime - timeBeforeAppStartTrigger
        val expectedFirstCallDelay = timings.appStartInterval
        
        assertTimingWithinTolerance(
            firstCallDelay,
            expectedFirstCallDelay,
            "First call should use APP_START timing"
        )
        assertEquals(1, callCount, "Should be called once for first call")

        delay(1000)
        val timeBeforeLocalDataTrigger = Clock.System.now().toEpochMilliseconds()
        scheduler.apply(Trigger.LOCAL_DATA_MODIFIED)

        taskCompleted = CompletableDeferred()
        val secondCallTime = taskCompleted.await()
        val secondCallDelay = secondCallTime - timeBeforeLocalDataTrigger
        val expectedSecondCallDelay = timings.localDataModifiedInterval
        
        assertTimingWithinTolerance(
            secondCallDelay,
            expectedSecondCallDelay,
            "Second call should use LOCAL_DATA_MODIFIED timing, not standard interval timing"
        )
        assertEquals(2, callCount, "Should be called twice total")
        
        scheduler.stop()
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `IMMEDIATE trigger should fire immediately after initialization`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        var taskCompleted = CompletableDeferred<Long>()
        var callCount = 0

        val scheduler = createScheduler(timings) {
            callCount++
            val callTime = Clock.System.now().toEpochMilliseconds()
            taskCompleted.complete(callTime)
            Result.success(Unit)
        }

        val timeBeforeImmediateTrigger = Clock.System.now().toEpochMilliseconds()
        scheduler.apply(Trigger.IMMEDIATE)

        val firstCallTime = taskCompleted.await()
        val firstCallDelay = firstCallTime - timeBeforeImmediateTrigger
        
        assertTrue(
            firstCallDelay < TIMING_TOLERANCE_MS,
            "IMMEDIATE trigger should fire immediately. Expected <${TIMING_TOLERANCE_MS}ms, got ${firstCallDelay}ms"
        )
        assertEquals(1, callCount, "Should be called once for immediate trigger")

        taskCompleted = CompletableDeferred()
        val timeBeforeSecondCall = Clock.System.now().toEpochMilliseconds()
        val secondCallTime = taskCompleted.await()
        val secondCallDelay = secondCallTime - timeBeforeSecondCall
        val expectedSecondCallDelay = timings.standardInterval
        
        assertTimingWithinTolerance(
            secondCallDelay,
            expectedSecondCallDelay,
            "Second call should use standard interval timing"
        )
        assertEquals(2, callCount, "Should be called twice total")
        
        scheduler.stop()
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `IMMEDIATE trigger after APP_START but before task execution should fire immediately`() = runTimeoutTest {
        val timings = OVERLAP_TEST_TIMINGS
        var taskCompleted = CompletableDeferred<Long>()
        var callCount = 0

        val scheduler = createScheduler(timings) {
            callCount++
            val callTime = Clock.System.now().toEpochMilliseconds()
            taskCompleted.complete(callTime)
            Result.success(Unit)
        }

        scheduler.apply(Trigger.APP_START)

        delay(1000)
        val timeBeforeImmediateTrigger = Clock.System.now().toEpochMilliseconds()
        scheduler.apply(Trigger.IMMEDIATE)

        val firstCallTime = taskCompleted.await()
        val firstCallDelay = firstCallTime - timeBeforeImmediateTrigger
        
        assertTrue(
            firstCallDelay < TIMING_TOLERANCE_MS,
            "IMMEDIATE trigger should fire immediately even after APP_START. Expected <${TIMING_TOLERANCE_MS}ms, got ${firstCallDelay}ms"
        )
        assertEquals(1, callCount, "Should be called once for immediate trigger")

        taskCompleted = CompletableDeferred()
        val timeBeforeSecondCall = Clock.System.now().toEpochMilliseconds()
        val secondCallTime = taskCompleted.await()
        val secondCallDelay = secondCallTime - timeBeforeSecondCall
        val expectedSecondCallDelay = timings.standardInterval
        
        assertTimingWithinTolerance(
            secondCallDelay,
            expectedSecondCallDelay,
            "Second call should use standard interval timing"
        )
        assertEquals(2, callCount, "Should be called twice total")
        
        scheduler.stop()
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `task function failures should retry with exponential backoff until maximum retries reached`() = runTimeoutTest {
        val timings = RETRY_TEST_TIMINGS
        var callCount = 0
        var maxRetriesError: Error? = null
        val maxRetriesDeferred = CompletableDeferred<Unit>()

        val scheduler = Scheduler(timings, { 
            callCount++
            Result.failure(Error("Test failure"))
        }, { error ->
            maxRetriesError = error
            maxRetriesDeferred.complete(Unit)
        })

        val timeBeforeTrigger = Clock.System.now().toEpochMilliseconds()
        scheduler.apply(Trigger.IMMEDIATE)

        // Wait for all retries to complete
        maxRetriesDeferred.await()

        assertEquals(timings.retryingTimings.maximumRetries + 1, callCount, 
            "Should be called maximum retries + 1 times (initial + retries)")
//        assertEquals("Test failure", maxRetriesError?.message, "Error should be passed to max retries callback")

        // Verify retry delays follow exponential backoff pattern
        // Note: We can't easily measure individual retry delays in this test structure,
        // but we can verify the total time taken is reasonable for the retry pattern
        val totalTime = Clock.System.now().toEpochMilliseconds() - timeBeforeTrigger
        val expectedMinTime = timings.retryingTimings.baseDelay * 
            (1 + timings.retryingTimings.multiplier + 
             timings.retryingTimings.multiplier * timings.retryingTimings.multiplier)
        
        assertTrue(totalTime >= expectedMinTime, 
            "Total time should account for retry delays. Expected at least ${expectedMinTime}ms, got ${totalTime}ms")
        
        scheduler.stop()
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `after task function failures standard interval scheduling should not occur`() = runTimeoutTest {
        val timings = SINGLE_RETRY_TIMINGS
        var callCount = 0
        val maxRetriesDeferred = CompletableDeferred<Unit>()

        val scheduler = Scheduler(timings, { 
            callCount++
            Result.failure(Error("Test failure"))
        }, { _ ->
            maxRetriesDeferred.complete(Unit)
        })

        scheduler.apply(Trigger.IMMEDIATE)

        // Wait for all retries to complete
        maxRetriesDeferred.await()

        // Wait for longer than the standard interval to ensure no additional scheduling occurs
        delay(timings.standardInterval + 1000)
        
        // Verify no additional calls were made after maximum retries
        assertEquals(timings.retryingTimings.maximumRetries + 1, callCount, 
            "Call count should remain at maximum retries + 1, no additional calls should be scheduled")
        
        scheduler.stop()
    }

    @OptIn(ExperimentalTime::class)
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
                Result.success(Unit)
            } else {
                Result.failure(Error("Test failure"))
            }
        }, { _ ->
            maxRetriesDeferred.complete(Unit)
        })

        scheduler.apply(Trigger.IMMEDIATE)

        // Wait for all retries to complete
        maxRetriesDeferred.await()

        assertEquals(timings.retryingTimings.maximumRetries + 1, callCount, 
            "Should be called maximum retries + 1 times before max retries reached")

        // Now set the task to succeed and apply a new trigger
        shouldSucceed = true
        val timeBeforeNewTrigger = Clock.System.now().toEpochMilliseconds()
        scheduler.apply(Trigger.LOCAL_DATA_MODIFIED)

        // Wait for the new task to complete
        newTaskDeferred.await()

        val timeAfterNewCall = Clock.System.now().toEpochMilliseconds()
        val newCallDelay = timeAfterNewCall - timeBeforeNewTrigger
        val expectedDelay = timings.localDataModifiedInterval

        assertEquals(timings.retryingTimings.maximumRetries + 2, callCount, 
            "Should be called one more time after applying new trigger")
        
        assertTimingWithinTolerance(
            newCallDelay,
            expectedDelay,
            "New trigger should use LOCAL_DATA_MODIFIED timing after maximum retries reached"
        )
        
        scheduler.stop()
    }
}
