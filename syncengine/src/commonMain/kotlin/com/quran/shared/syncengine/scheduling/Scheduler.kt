package com.quran.shared.syncengine.scheduling

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

enum class Trigger {
    APP_START,
    LOCAL_DATA_MODIFIED,
    IMMEDIATE
}

// In milliseconds
data class SchedulerTimings(
    val standardInterval: Long,
    val appStartInterval: Long,
    val localDataModifiedInterval: Long,
    val retryingTimings: RetryingTimings
)

// In milliseconds
data class RetryingTimings(
    val baseDelay: Long,
    val multiplier: Double,
    val maximumRetries: Int
)

private val DefaultTimings = SchedulerTimings(
    standardInterval = 30 * 60 * 1000,
    appStartInterval = 30 * 1000,
    localDataModifiedInterval = 5 * 1000,
    retryingTimings = RetryingTimings(baseDelay = 200, multiplier = 2.5, maximumRetries = 5)
)

private sealed class SchedulerState {
    data object Initialized: SchedulerState()
    data object RegularWait: SchedulerState()
    data class WaitingForReply(val original: SchedulerState): SchedulerState()
    data class Replied(val original: SchedulerState): SchedulerState()
    data class Triggered(val trigger: Trigger): SchedulerState()
    data class Retrying(val original: SchedulerState, val retryNumber: Int): SchedulerState()
}

/**
 * A scheduler that manages the execution of a task function with configurable timing and retry logic.
 * 
 * ## Error Handling
 * The taskFunction should throw an exception to indicate failure. The scheduler will:
 * - Catch exceptions and retry according to the configured retry timings
 * - After maximum retries are exhausted, report the final exception to reachedMaximumFailureRetries
 * - Continue normal operation if taskFunction completes without throwing
 * 
 * @param timings Configuration for scheduling intervals and retry behavior
 * @param taskFunction The task to execute. Should throw an exception on failure.
 * @param reachedMaximumFailureRetries Called when max retries are exhausted with the final exception
 */
@OptIn(ExperimentalTime::class)
class Scheduler(
    val timings: SchedulerTimings,
    val taskFunction: suspend () -> Unit,
    val reachedMaximumFailureRetries: suspend (Exception) -> Unit,
) {
    private var state: SchedulerState = SchedulerState.Initialized
    private var expectedExecutionTime: Long? = null

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var currentJob: Job? = null

    fun apply(trigger: Trigger) {
        when(trigger) {
            Trigger.APP_START -> scheduleAppStart()
            Trigger.LOCAL_DATA_MODIFIED -> scheduleLocalDataModified()
            Trigger.IMMEDIATE -> scheduleImmediately()
        }
    }

    fun stop() {
        currentJob?.cancel()
        currentJob = null
        expectedExecutionTime = null
    }

    private fun schedule(time: Long, newState: SchedulerState) {
        val currentTime = Clock.System.now().toEpochMilliseconds()
        val firingTime = currentTime + time
        if (currentlyScheduled() && firingTime >= (expectedExecutionTime ?: 0)) {
            println("Ignored the schedule")
            return
        }
        expectedExecutionTime = firingTime

        currentJob?.cancel()

        this.state = newState
        println("Scheduling in $time ms. Expected firing time: ${Instant.fromEpochMilliseconds(firingTime)}. State: $newState")
        currentJob = scope.launch {
            println("In the job")
            kotlinx.coroutines.delay(time)

            state = SchedulerState.WaitingForReply(original = newState)
            try {
                taskFunction()
                state = SchedulerState.Replied(original = newState)
                scheduleDefault()
            } catch (e: Exception) {
                state = SchedulerState.Replied(original = newState)
                scheduleForFailure(e)
            }
        }
    }

    private fun currentlyScheduled(): Boolean =
        when (state) {
            SchedulerState.Initialized, is SchedulerState.Replied -> false
            is SchedulerState.WaitingForReply -> false
            SchedulerState.RegularWait, is SchedulerState.Triggered, is SchedulerState.Retrying -> true
        }


    private fun scheduleAppStart() {
        schedule(timings.appStartInterval, SchedulerState.Triggered(Trigger.APP_START))
    }

    private fun scheduleDefault() {
        println("scheduleDefault")
        schedule(timings.standardInterval, SchedulerState.RegularWait)
    }

    private fun scheduleLocalDataModified() {
        schedule(timings.localDataModifiedInterval, SchedulerState.Triggered(Trigger.LOCAL_DATA_MODIFIED))
    }

    private fun scheduleImmediately() {
        schedule(0, SchedulerState.Triggered(Trigger.IMMEDIATE))
    }

    private fun scheduleForFailure(exception: Exception) {
        val state = this.state
        if (state is SchedulerState.Replied) {
            val count = state.original.getRetryCount()
            println("Original state: ${state.original}")
            println("Got count of retries: $count")
            if (count < timings.retryingTimings.maximumRetries) {
                val nextCount = count + 1
                val nextTime = (timings.retryingTimings.baseDelay * timings.retryingTimings.multiplier.pow(count)).toLong()
                println("Time for next job: $nextTime")
                schedule(nextTime, SchedulerState.Retrying(original = state.original.originalState(),
                    retryNumber = nextCount))
            }
            else {
                reportFailureAndSeize(exception)
            }
        }
    }

    private fun reportFailureAndSeize(exception: Exception) {
        scope.launch {
            reachedMaximumFailureRetries(exception)
        }
        this.state = SchedulerState.Initialized
        this.currentJob?.cancel()
        this.currentJob = null
        this.expectedExecutionTime = null
    }
}

private fun SchedulerState.getRetryCount(): Int = when (this) {
    is SchedulerState.Retrying -> this.retryNumber
    else -> 0
}

private fun SchedulerState.originalState(): SchedulerState = when(this) {
    is SchedulerState.Retrying -> original
    is SchedulerState.Replied -> original
    is SchedulerState.WaitingForReply -> original
    is SchedulerState.Triggered, SchedulerState.Initialized, SchedulerState.RegularWait -> this
}