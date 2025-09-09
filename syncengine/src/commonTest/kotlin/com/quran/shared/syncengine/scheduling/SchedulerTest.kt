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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class SchedulerTest {

    companion object {
        // Github Actions's instances are not noticeably slower than local machines. A high
        // tolerance is needed.
        private const val TIMING_TOLERANCE_MS = 250L
        private const val DEFAULT_TIMEOUT_MS = 6_000L
        
        private val STANDARD_TEST_TIMINGS = SchedulerTimings(
            appRefreshInterval = 600L,
            standardInterval = 900L,
            localDataModifiedInterval = 300L,
            failureRetryingConfig = FailureRetryingConfig(baseDelay = 300, multiplier = 2.5, maximumRetries = 3)
        )
        
        private val OVERLAP_TEST_TIMINGS = SchedulerTimings(
            appRefreshInterval = 600L,
            standardInterval = 1500L,
            localDataModifiedInterval = 300L,
            failureRetryingConfig = FailureRetryingConfig(baseDelay = 300, multiplier = 2.5, maximumRetries = 3)
        )
        
        private val SINGLE_RETRY_TIMINGS = SchedulerTimings(
            appRefreshInterval = 600L,
            standardInterval = 900L,
            localDataModifiedInterval = 400L,
            failureRetryingConfig = FailureRetryingConfig(baseDelay = 200, multiplier = 2.5, maximumRetries = 1)
        )
        
        @OptIn(ExperimentalTime::class)
        private fun currentTimeMs(): Long = Clock.System.now().toEpochMilliseconds()
        
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

    private fun runTimeoutTest(testBody: suspend TestScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                testBody()
            }
        }
    }


    @Test
    fun `taskFunction should be called eventually with correct timing`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        val taskCompleted = CompletableDeferred<Long>()

        val scheduler = Scheduler(timings, {
            taskCompleted.complete(currentTimeMs())
        }, { _ -> })

        val timeBeforeCall = currentTimeMs()
        scheduler.invoke(Trigger.APP_REFRESH)

        val timeAfterCall = taskCompleted.await()

        assertTrue(timeAfterCall > timeBeforeCall, "Task must be called after apply")
        
        val actualDelay = timeAfterCall - timeBeforeCall
        val expectedDelay = timings.appRefreshInterval
        assertTrue(
            actualDelay - expectedDelay < TIMING_TOLERANCE_MS,
            "Task should be called quickly with no delay, took ${actualDelay}ms while expected is ${timings.appRefreshInterval}ms"
        )
        scheduler.stop()
    }

    @Test
    fun `scheduler should keep firing with the standard interval`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        var taskCompleted = CompletableDeferred<Int>()

        var count = 0
        val timeBeforeTest = currentTimeMs()
        val scheduler = Scheduler(timings, {
            count++
            taskCompleted.complete(count)
        }, { _ -> })

        scheduler.invoke(Trigger.APP_REFRESH)

        taskCompleted.await()
        taskCompleted = CompletableDeferred()
        val returnedCount = taskCompleted.await()
        val timeAfterTest = currentTimeMs()

        assertEquals(2, returnedCount, "Expected to be called twice.")
        
        val timeDelay = timeAfterTest - timeBeforeTest
        val expectedTotalDelay = timings.appRefreshInterval + timings.standardInterval
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

        val scheduler = Scheduler(timings, {
            callCount++
            taskCompleted.complete(callCount)
        }, { _ -> })

        scheduler.invoke(Trigger.APP_REFRESH)

        taskCompleted.await()
        taskCompleted = CompletableDeferred()
        taskCompleted.await()

        scheduler.stop()

        val waiting = CompletableDeferred<Unit>()
        backgroundScope.launch {
            delay(100)
            waiting.complete(Unit)
        }
        waiting.await()

        assertEquals(2, callCount, "Expect only two calls before stop is called.")
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
        
        delay(100)
        val timeBeforeDataModifiedTrigger = currentTimeMs()
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)

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
        val firstCallDelay = firstCallTime - timeBeforeFirstTrigger
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

    @Test
    fun `APP_START trigger before previous LOCAL_DATA_MODIFIED is delivered should be ignored`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        var taskCompleted = CompletableDeferred<Long>()
        var callCount = 0

        val scheduler = Scheduler(timings, {
            callCount++
            taskCompleted.complete(currentTimeMs())
        }, { _ -> })

        val timeBeforeLocalDataTrigger = currentTimeMs()
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)

        delay(100)
        scheduler.invoke(Trigger.APP_REFRESH)

        val firstCallTime = taskCompleted.await()
        val firstCallDelay = firstCallTime - timeBeforeLocalDataTrigger
        val expectedFirstCallDelay = timings.localDataModifiedInterval
        
        assertTimingWithinTolerance(
            firstCallDelay,
            expectedFirstCallDelay,
            "First call should use LOCAL_DATA_MODIFIED timing, not APP_START timing"
        )
        assertEquals(1, callCount, "Should be called once for first call")

        val timeBeforeSecondCall = currentTimeMs()
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
        val firstCallDelay = firstCallTime - timeBeforeAppStartTrigger
        val expectedFirstCallDelay = timings.appRefreshInterval
        
        assertTimingWithinTolerance(
            firstCallDelay,
            expectedFirstCallDelay,
            "First call should use APP_START timing"
        )
        assertEquals(1, callCount, "Should be called once for first call")

        delay(50)
        val timeBeforeLocalDataTrigger = currentTimeMs()
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)

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

    @Test
    fun `IMMEDIATE trigger should fire immediately after initialization`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        var taskCompleted = CompletableDeferred<Long>()
        var callCount = 0

        val scheduler = Scheduler(timings, {
            callCount++
            taskCompleted.complete(currentTimeMs())
        }, { _ -> })

        val timeBeforeImmediateTrigger = currentTimeMs()
        scheduler.invoke(Trigger.IMMEDIATE)

        val firstCallTime = taskCompleted.await()
        val firstCallDelay = firstCallTime - timeBeforeImmediateTrigger
        
        assertTrue(
            firstCallDelay < TIMING_TOLERANCE_MS,
            "IMMEDIATE trigger should fire immediately. Expected <${TIMING_TOLERANCE_MS}ms, got ${firstCallDelay}ms"
        )
        assertEquals(1, callCount, "Should be called once for immediate trigger")

        taskCompleted = CompletableDeferred()
        val timeBeforeSecondCall = currentTimeMs()
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

    @Test
    fun `IMMEDIATE trigger after APP_START but before task execution should fire immediately`() = runTimeoutTest {
        val timings = OVERLAP_TEST_TIMINGS
        var taskCompleted = CompletableDeferred<Long>()
        var callCount = 0

        val scheduler = Scheduler(timings, {
            callCount++
            taskCompleted.complete(currentTimeMs())
        }, { _ -> })

        scheduler.invoke(Trigger.APP_REFRESH)

        delay(50)
        val timeBeforeImmediateTrigger = currentTimeMs()
        scheduler.invoke(Trigger.IMMEDIATE)

        val firstCallTime = taskCompleted.await()
        val firstCallDelay = firstCallTime - timeBeforeImmediateTrigger
        
        assertTrue(
            firstCallDelay < TIMING_TOLERANCE_MS,
            "IMMEDIATE trigger should fire immediately even after APP_START. Expected <${TIMING_TOLERANCE_MS}ms, got ${firstCallDelay}ms"
        )
        assertEquals(1, callCount, "Should be called once for immediate trigger")

        taskCompleted = CompletableDeferred()
        val timeBeforeSecondCall = currentTimeMs()
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

        // Wait for all retries to complete
        maxRetriesDeferred.await()

        assertEquals(timings.failureRetryingConfig.maximumRetries + 1, callCount,
            "Should be called maximum retries + 1 times (initial + retries)")
        assertEquals("Test failure", maxRetriesError?.message, "Exception should be passed to max retries callback")

        val totalTime = currentTimeMs() - timeBeforeTrigger
        val expectedMinTime = timings.failureRetryingConfig.baseDelay *
            (1 + timings.failureRetryingConfig.multiplier +
             timings.failureRetryingConfig.multiplier * timings.failureRetryingConfig.multiplier)
        
        assertTrue(totalTime >= expectedMinTime, 
            "Total time should account for retry delays. Expected at least ${expectedMinTime}ms, got ${totalTime}ms")
        
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

        delay(timings.standardInterval + 50)
        
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

        // Wait for all retries to complete
        maxRetriesDeferred.await()

        assertEquals(timings.failureRetryingConfig.maximumRetries + 1, callCount,
            "Should be called maximum retries + 1 times before max retries reached")

        // Now set the task to succeed and apply a new trigger
        shouldSucceed = true
        val timeBeforeNewTrigger = currentTimeMs()
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)

        // Wait for the new task to complete
        newTaskDeferred.await()

        val timeAfterNewCall = currentTimeMs()
        val newCallDelay = timeAfterNewCall - timeBeforeNewTrigger
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
    fun `APP_START trigger should be ignored during job execution and next job uses standard timing`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        val taskStarted = CompletableDeferred<Unit>()
        val taskCanProceed = CompletableDeferred<Unit>()
        val secondTaskCompleted = CompletableDeferred<Long>()
        var callCount = 0

        val scheduler = Scheduler(timings, {
            callCount++
            if (callCount == 1) {
//                taskStarted.complete(Unit)
                taskCanProceed.await() // Block until test allows continuation
            } else if (callCount == 2) {
                secondTaskCompleted.complete(currentTimeMs())
            }
        }, { _ -> })

        // Start initial trigger
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)
//        taskStarted.await() // Wait for task to start
        delay(timings.localDataModifiedInterval / 2)
        val timeAfterFirstJob = currentTimeMs()
        scheduler.invoke(Trigger.APP_REFRESH)
        
        // Allow first task to complete successfully
        taskCanProceed.complete(Unit)
        
        // Wait for second task to complete (should be scheduled with standard timing)
        val secondTaskTime = secondTaskCompleted.await()
        
        // Verify timing - should use standard interval, NOT APP_START interval
        val actualDelay = secondTaskTime - timeAfterFirstJob
        assertTimingWithinTolerance(
            actualDelay,
            timings.standardInterval,
            "Next job after ignored APP_START should use standard timing"
        )

        val appStartDifference = actualDelay - timings.standardInterval
        assertTrue(
            appStartDifference < TIMING_TOLERANCE_MS,
            "Next job should NOT use APP_START timing (${timings.appRefreshInterval}ms). " +
            "Actual: ${actualDelay}ms, difference from APP_START timing: ${appStartDifference}ms"
        )
        
        assertEquals(2, callCount, "Should execute initial task and next scheduled task")
        
        scheduler.stop()
    }

    @Test
    fun `LOCAL_DATA_MODIFIED during successful job should schedule LOCAL_DATA_MODIFIED next`() = runTimeoutTest {
        val timings = STANDARD_TEST_TIMINGS
        val taskStarted = CompletableDeferred<Unit>()
        val taskCanProceed = CompletableDeferred<Unit>()
        val secondTaskCompleted = CompletableDeferred<Long>()
        var callCount = 0

        val scheduler = Scheduler(timings, {
            callCount++
            if (callCount == 1) {
                taskStarted.complete(Unit)
                taskCanProceed.await() // Block until test allows continuation
            } else if (callCount == 2) {
                secondTaskCompleted.complete(currentTimeMs())
            }
        }, { _ -> })

        // Start initial trigger
        scheduler.invoke(Trigger.APP_REFRESH)
        taskStarted.await() // Wait for task to start

        // Apply LOCAL_DATA_MODIFIED while job is running
        val timeBeforeTrigger = currentTimeMs()
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)
        
        // Allow first task to complete successfully
        taskCanProceed.complete(Unit)
        
        // Wait for second task to complete
        val secondTaskTime = secondTaskCompleted.await()
        
        // Verify timing - should be LOCAL_DATA_MODIFIED interval from trigger time
        val actualDelay = secondTaskTime - timeBeforeTrigger
        assertTimingWithinTolerance(
            actualDelay, 
            timings.localDataModifiedInterval,
            "LOCAL_DATA_MODIFIED during successful job should schedule with LOCAL_DATA_MODIFIED timing"
        )
        
        assertEquals(2, callCount, "Should have executed both initial and triggered tasks")
        
        scheduler.stop()
    }

    @Test
    fun `LOCAL_DATA_MODIFIED during failed job should be ignored`() = runTimeoutTest {
        val timings = SINGLE_RETRY_TIMINGS // Only 1 retry for faster test
        val taskStarted = CompletableDeferred<Unit>()
        val taskCanProceed = CompletableDeferred<Unit>()
        val maxRetriesReached = CompletableDeferred<Unit>()
        var callCount = 0
        val callCountBeforeDelay: Int
        val callCountAfterDelay: Int

        val scheduler = Scheduler(timings, {
            callCount++
            if (callCount == 1) {
                taskStarted.complete(Unit)
                taskCanProceed.await() // Block until test allows continuation
                throw Exception("Simulated failure")
            } else {
                // Retry attempt - also fail
                throw Exception("Simulated retry failure")
            }
        }, { _ -> 
            maxRetriesReached.complete(Unit)
        })

        // Start initial trigger
        scheduler.invoke(Trigger.APP_REFRESH)
        taskStarted.await() // Wait for task to start

        // Apply LOCAL_DATA_MODIFIED while job is running and will fail
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)
        
        // Allow first task to fail
        taskCanProceed.complete(Unit)
        
        // Wait for retries to exhaust
        maxRetriesReached.await()
        
        // Record call count after retries are done
        callCountBeforeDelay = callCount
        
        // Wait significantly longer to verify no additional tasks are scheduled from the ignored trigger
        // Use both LOCAL_DATA_MODIFIED interval and standard interval to be thorough
        val verificationDelay = maxOf(
            timings.localDataModifiedInterval + TIMING_TOLERANCE_MS,
            timings.standardInterval + TIMING_TOLERANCE_MS
        )
        delay(verificationDelay)
        
        // Record call count after extended delay
        callCountAfterDelay = callCount
        
        assertEquals(2, callCountBeforeDelay, "Should have executed initial task + 1 retry only after max retries reached")
        assertEquals(callCountBeforeDelay, callCountAfterDelay, "No additional tasks should be scheduled after extended delay - LOCAL_DATA_MODIFIED trigger was ignored")
        assertEquals(2, callCount, "Final verification: LOCAL_DATA_MODIFIED trigger during failed job was properly ignored")
        
        scheduler.stop()
    }

    @Test
    fun `LOCAL_DATA_MODIFIED during retry should be ignored`() = runTimeoutTest {
        val timings = SINGLE_RETRY_TIMINGS // Only 1 retry for faster test
        val taskStarted = CompletableDeferred<Unit>()
        val retryStarted = CompletableDeferred<Unit>()
        val retryCanProceed = CompletableDeferred<Unit>()
        val maxRetriesReached = CompletableDeferred<Unit>()
        var callCount = 0
        val callCountBeforeDelay: Int
        val callCountAfterDelay: Int

        val scheduler = Scheduler(timings, {
            callCount++
            if (callCount == 1) {
//                taskStarted.complete(Unit)
                // First task fails immediately
                throw Exception("Simulated initial failure")
            } else {
                // Retry attempt - block here so we can apply trigger during retry
//                retryStarted.complete(Unit)
                retryCanProceed.await() // Block until test allows continuation
                throw Exception("Simulated retry failure")
            }
        }, { _ -> 
            maxRetriesReached.complete(Unit)
        })

        // Start initial trigger
        scheduler.invoke(Trigger.APP_REFRESH)
        delay(timings.appRefreshInterval)
//        taskStarted.await() // Wait for initial task to start and fail
        
        // Wait for retry to start
//        retryStarted.await() // Wait for retry to start
        
        // Apply LOCAL_DATA_MODIFIED while RETRY is running (should be ignored)
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)
        
        // Allow retry to fail
        retryCanProceed.complete(Unit)
        
        // Wait for retries to exhaust
        maxRetriesReached.await()
        
        // Record call count after retries are done
        callCountBeforeDelay = callCount
        
        // Wait significantly longer to verify no additional tasks are scheduled from the ignored trigger
        // Use both LOCAL_DATA_MODIFIED interval and standard interval to be thorough
        val verificationDelay = maxOf(
            timings.localDataModifiedInterval + TIMING_TOLERANCE_MS,
            timings.standardInterval + TIMING_TOLERANCE_MS
        )
        delay(verificationDelay)
        
        // Record call count after extended delay
        callCountAfterDelay = callCount
        
        assertEquals(2, callCountBeforeDelay, "Should have executed initial task + 1 retry only after max retries reached")
        assertEquals(callCountBeforeDelay, callCountAfterDelay, "No additional tasks should be scheduled after extended delay - LOCAL_DATA_MODIFIED trigger during retry was ignored")
        assertEquals(2, callCount, "Final verification: LOCAL_DATA_MODIFIED trigger during retry was properly ignored")
        
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

        // Stop the scheduler first
        scheduler.stop()
        
        // Give it a moment to process the stop
        delay(50)

        // Try to invoke triggers after stopping - they should be ignored
        scheduler.invoke(Trigger.APP_REFRESH)
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)
        scheduler.invoke(Trigger.IMMEDIATE)
        
        // Wait a reasonable amount of time to ensure no task is executed
        delay(timings.appRefreshInterval + 200)
        
        // Verify that the task was never called
        assertEquals(0, callCount, "Task should not be called after scheduler is stopped")
        
        // Verify that the CompletableDeferred is not completed
        assertTrue(taskCompleted.isCompleted.not(), "Task completion should not be triggered after stop")
    }

    private fun assertTimingWithinTolerance(actual: Long, expected: Long, message: String) {
        val difference = kotlin.math.abs(actual - expected)
        assertTrue(
            difference <= TIMING_TOLERANCE_MS,
            "$message. Expected: ${expected}ms ± ${TIMING_TOLERANCE_MS}ms, Actual: ${actual}ms (difference: ${difference}ms)"
        )
    }
}
