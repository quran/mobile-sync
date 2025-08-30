package com.quran.shared.syncengine.scheduling

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    @Test
    fun `apply with APP_START should schedule task with correct timing`() {
        val timings = SchedulerTimings(standardInterval = 30L)
        var taskFunctionCalled = false
        
        val scheduler = Scheduler(timings, {
            taskFunctionCalled = true
            true
        })

        // Just verify that apply doesn't throw an exception
        scheduler.apply(Trigger.APP_START)
        
        // The task function should not be called immediately
        assertFalse(taskFunctionCalled, "Task function should not be called immediately")
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `taskFunction should be called eventually`() = runTest {
        val timings = SchedulerTimings(standardInterval = 1L) // No delay for testing
        val taskCompleted = CompletableDeferred<Long>()

        val scheduler = Scheduler(timings, {
            val callTime = Clock.System.now().toEpochMilliseconds()
            taskCompleted.complete(callTime)
            true
        })

        val timeBeforeCall = Clock.System.now().toEpochMilliseconds()
        scheduler.apply(Trigger.APP_START)

        // Wait for the task to complete
        val timeAfterCall = taskCompleted.await()

        assertTrue(timeAfterCall > timeBeforeCall, "Task must be called after apply")
        
        // Verify the timing is reasonable (within 100ms for no delay)
        val actualDelay = timeAfterCall - timeBeforeCall
        assertTrue(actualDelay - timings.standardInterval * 1000 < 100,
            "Task should be called quickly with no delay, took ${actualDelay}ms while expected is ${timings.standardInterval}s")
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `scheduler should keep firing with same interval`() = runTest {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5 * 1000) {
                val timings = SchedulerTimings(standardInterval = 1L)
                var taskCompleted = CompletableDeferred<Int>()

                var count = 0
                val timeBeforeTest = Clock.System.now().toEpochMilliseconds()
                val scheduler = Scheduler(timings, {
                    count++
                    println("Scheduler task invoked. Calls count: $count")
                    taskCompleted.complete(count)
                    true // Return true to continue scheduling
                })

                scheduler.apply(Trigger.APP_START)

                taskCompleted.await()
                println("The first call")
                taskCompleted = CompletableDeferred()
                val returnedCount = taskCompleted.await()
                println("The second call")
                val timeAfterTest = Clock.System.now().toEpochMilliseconds()

                // Verify that the scheduler fired multiple times as expected
                assertEquals(2, returnedCount, "Expected to be called twice.")
                val timeDelay = timeAfterTest - timeBeforeTest
                assertTrue(
                    timeDelay - timings.standardInterval * 2 * 1000 < 100,
                    "Expected to wait the standard interval for both calls. Time delay: $timeDelay"
                )
                println("Time delay: $timeDelay")
                scheduler.stop()
                println("Stopped the scheduler")
            }
        }
    }

    @Test
    fun `scheduler should stop when stop is called`() = runTest {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5 * 1000) {
                val timings = SchedulerTimings(standardInterval = 1L) // No delay for testing
                var taskCompleted = CompletableDeferred<Int>()
                var callCount = 0

                val scheduler = Scheduler(timings, {
                    callCount++
                    taskCompleted.complete(callCount)
                    true // Return false to simulate stopping after first call
                })

                scheduler.apply(Trigger.APP_START)

                // Await for two invocations.
                taskCompleted.await()
                println("The first call")
                taskCompleted = CompletableDeferred()
                taskCompleted.await()
                println("The second call")

                scheduler.stop()

                val waiting = CompletableDeferred<Unit>()
                backgroundScope.launch {
                    println("Waiting job")
                    delay(1000)
                    println("After the delay")
                    waiting.complete(Unit)
                }
                waiting.await()

                assertEquals(2, callCount, "Expect only two calls before stop is called.")
            }
        }
    }
}
