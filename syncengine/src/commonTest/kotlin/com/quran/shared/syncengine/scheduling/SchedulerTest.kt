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
        private const val TIMING_TOLERANCE_MS = 100L
        private const val DEFAULT_TIMEOUT_MS = 15_000L
        
        private val STANDARD_TEST_TIMINGS = SchedulerTimings(
            appStartInterval = 200L,
            standardInterval = 300L,
            localDataModifiedInterval = 100L,
            retryingTimings = RetryingTimings(baseDelay = 100, multiplier = 2.5, maximumRetries = 3)
        )
        
        private val OVERLAP_TEST_TIMINGS = SchedulerTimings(
            appStartInterval = 200L,
            standardInterval = 500L,
            localDataModifiedInterval = 100L,
            retryingTimings = RetryingTimings(baseDelay = 100, multiplier = 2.5, maximumRetries = 3)
        )
        
        private val SINGLE_RETRY_TIMINGS = SchedulerTimings(
            appStartInterval = 200L,
            standardInterval = 300L,
            localDataModifiedInterval = 100L,
            retryingTimings = RetryingTimings(baseDelay = 100, multiplier = 2.5, maximumRetries = 1)
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
        scheduler.apply(Trigger.APP_START)

        val timeAfterCall = taskCompleted.await()

        assertTrue(timeAfterCall > timeBeforeCall, "Task must be called after apply")
        
        val actualDelay = timeAfterCall - timeBeforeCall
        val expectedDelay = timings.appStartInterval
        assertTrue(
            actualDelay - expectedDelay < TIMING_TOLERANCE_MS,
            "Task should be called quickly with no delay, took ${actualDelay}ms while expected is ${timings.appStartInterval}ms"
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

        scheduler.apply(Trigger.APP_START)

        taskCompleted.await()
        taskCompleted = CompletableDeferred()
        val returnedCount = taskCompleted.await()
        val timeAfterTest = currentTimeMs()

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

        val scheduler = Scheduler(timings, {
            callCount++
            taskCompleted.complete(callCount)
        }, { _ -> })

        scheduler.apply(Trigger.APP_START)

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

        scheduler.apply(Trigger.APP_START)
        
        delay(100)
        val timeBeforeDataModifiedTrigger = currentTimeMs()
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

        delay(50)
        val timeBeforeLocalDataTrigger = currentTimeMs()
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
        scheduler.apply(Trigger.IMMEDIATE)

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

        scheduler.apply(Trigger.APP_START)

        delay(50)
        val timeBeforeImmediateTrigger = currentTimeMs()
        scheduler.apply(Trigger.IMMEDIATE)

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
        scheduler.apply(Trigger.IMMEDIATE)

        // Wait for all retries to complete
        maxRetriesDeferred.await()

        assertEquals(timings.retryingTimings.maximumRetries + 1, callCount, 
            "Should be called maximum retries + 1 times (initial + retries)")
        assertEquals("Test failure", maxRetriesError?.message, "Exception should be passed to max retries callback")

        val totalTime = currentTimeMs() - timeBeforeTrigger
        val expectedMinTime = timings.retryingTimings.baseDelay * 
            (1 + timings.retryingTimings.multiplier + 
             timings.retryingTimings.multiplier * timings.retryingTimings.multiplier)
        
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

        scheduler.apply(Trigger.IMMEDIATE)

        maxRetriesDeferred.await()

        delay(timings.standardInterval + 50)
        
        assertEquals(timings.retryingTimings.maximumRetries + 1, callCount,
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

        scheduler.apply(Trigger.IMMEDIATE)

        // Wait for all retries to complete
        maxRetriesDeferred.await()

        assertEquals(timings.retryingTimings.maximumRetries + 1, callCount, 
            "Should be called maximum retries + 1 times before max retries reached")

        // Now set the task to succeed and apply a new trigger
        shouldSucceed = true
        val timeBeforeNewTrigger = currentTimeMs()
        scheduler.apply(Trigger.LOCAL_DATA_MODIFIED)

        // Wait for the new task to complete
        newTaskDeferred.await()

        val timeAfterNewCall = currentTimeMs()
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
