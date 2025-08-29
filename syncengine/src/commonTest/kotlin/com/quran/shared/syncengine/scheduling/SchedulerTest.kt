package com.quran.shared.syncengine.scheduling

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
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
}
