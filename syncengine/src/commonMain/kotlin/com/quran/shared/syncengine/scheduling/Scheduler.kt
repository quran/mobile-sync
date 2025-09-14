package com.quran.shared.syncengine.scheduling

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

enum class Trigger {
    APP_REFRESH,
    LOCAL_DATA_MODIFIED,
    IMMEDIATE
}

data class SchedulerTimings(
    val standardInterval: Duration,
    val appRefreshInterval: Duration,
    val localDataModifiedInterval: Duration,
    val failureRetryingConfig: FailureRetryingConfig
)

data class FailureRetryingConfig(
    val baseDelay: Duration,
    val multiplier: Double,
    val maximumRetries: Int
)

private val DefaultTimings = SchedulerTimings(
    standardInterval = 30.minutes,
    appRefreshInterval = 30.seconds,
    localDataModifiedInterval = 5.seconds,
    failureRetryingConfig = FailureRetryingConfig(baseDelay = 200.milliseconds, multiplier = 2.5, maximumRetries = 5)
)

private sealed class SchedulerState {
    /** Scheduler is operational, but nothing is scheduled.*/
    data object Idle: SchedulerState()
    /** A non-triggered standard job has been scheduled. */
    data object StandardDelay: SchedulerState()
    /** The job has been fired, and the scheduler is waiting on the taskFunction to return. */
    data class WaitingForReply(val original: SchedulerState): SchedulerState()
    /** The task function's response is being processed. */
    data class Replied(val original: SchedulerState): SchedulerState()
    /** A job is currently scheduled due to the associated trigger. */
    data class Triggered(val trigger: Trigger): SchedulerState()
    /** A job is currently scheduled to retry an earlier failed taskFunction execution. */
    data class Retrying(val original: SchedulerState, val retryNumber: Int): SchedulerState()
    /** Scheduler has been stopped and will not accept any further triggers. */
    data object Stopped: SchedulerState()
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
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val logger = Logger.withTag("Scheduler")

    private var mutex = Mutex()

    // region: Synchronized internal state
    private var bufferedTrigger: Trigger? = null
    private var state: SchedulerState = SchedulerState.Idle
    private var expectedExecutionTime: Long? = null
    private var currentJob: Job? = null
    //endregion:

    // region: Public
    fun invoke(trigger: Trigger) {
        scope.launch {
            processInvokedTrigger(trigger)
        }
    }

    // Entry point. Starts a critical section
    fun stop() {
        scope.launch {
            executeStop()
        }
    }
    // endregion:

    // region: Internal-state entry points

    // Entry point: starts a critical section
    private suspend fun processInvokedTrigger(trigger: Trigger) {
        mutex.withLock {
            when (state) {
                SchedulerState.Idle, SchedulerState.StandardDelay, is SchedulerState.Triggered -> {
                    logger.d { "Trigger invoked: $trigger" }
                    executeTrigger(trigger)
                }

                is SchedulerState.WaitingForReply, is SchedulerState.Replied -> {
                    buffer(trigger)
                }

                is SchedulerState.Retrying -> {}
                
                SchedulerState.Stopped -> {
                    logger.d { "Ignoring trigger $trigger: scheduler has been stopped" }
                }
            }
        }
    }

    // Entry point: starts a critical section
    private suspend fun executeStop() {
        mutex.withLock {
            logger.i { "Stopping scheduler, cancelling current job" }
            currentJob?.cancel()
            currentJob = null
            expectedExecutionTime = null
            bufferedTrigger = null
            state = SchedulerState.Stopped
        }
    }

    // Entry point. Starts a critical section
    private suspend fun timeTaskFunctionCall(timeMS: Long, startingState: SchedulerState) {
        kotlinx.coroutines.delay(timeMS)

        logger.d { "Starting scheduled job execution" }
        mutex.withLock {
            state = SchedulerState.WaitingForReply(original = startingState)
        }

        logger.d { "Executing task function, state: WaitingForReply" }
        try {
            taskFunction()

            mutex.withLock {
                state = SchedulerState.Replied(original = startingState)
                logger.i { "Task completed successfully" }
                processSuccess()
            }

        } catch (e: Exception) {
            mutex.withLock {
                state = SchedulerState.Replied(original = startingState)
                logger.e { "Task failed with exception: ${e.message}, processing failure logic" }
                scheduleForFailure(e)
            }
        }
    }
    // endregion:

    // region: Internal-state manipulators. Critical-section bound

    // Critical-section bound
    private fun buffer(trigger: Trigger) {
        when(trigger) {
            Trigger.APP_REFRESH, Trigger.IMMEDIATE -> {
                logger.d { "Ignoring redundant trigger: $trigger. Job is already being processed" }
                return
            }
            Trigger.LOCAL_DATA_MODIFIED -> {
                logger.d { "Buffering trigger: $trigger after job is done." }
                bufferedTrigger = trigger
            }
        }
    }

    // Critical-section bound
    private fun executeTrigger(trigger: Trigger) {
        when (trigger) {
            Trigger.APP_REFRESH -> schedule(timings.appRefreshInterval, SchedulerState.Triggered(Trigger.APP_REFRESH))
            Trigger.LOCAL_DATA_MODIFIED -> schedule(timings.localDataModifiedInterval, SchedulerState.Triggered(Trigger.LOCAL_DATA_MODIFIED))
            Trigger.IMMEDIATE -> schedule(Duration.ZERO, SchedulerState.Triggered(Trigger.IMMEDIATE))
        }
    }

    // Critical-section bound
    private fun schedule(time: Duration, newState: SchedulerState) {
        val currentTime = Clock.System.now().toEpochMilliseconds()
        val firingTime = currentTime + time.inWholeMilliseconds
        if (currentlyScheduled() && firingTime >= (expectedExecutionTime ?: 0)) {
            logger.d { "Ignored schedule request: new firing time $firingTime >= current expected time $expectedExecutionTime" }
            return
        }
        expectedExecutionTime = firingTime

        currentJob?.cancel()

        this.state = newState
        logger.d { "Scheduling task in $time. Expected firing time: ${Instant.fromEpochMilliseconds(firingTime)}, State: $newState" }
        currentJob = scope.launch {
            // This will start a critical section.
            timeTaskFunctionCall(time.inWholeMilliseconds, newState)
        }
    }

    // Critical-section bound
    private fun currentlyScheduled(): Boolean =
        when (state) {
            SchedulerState.Idle, is SchedulerState.Replied, SchedulerState.Stopped -> false
            is SchedulerState.WaitingForReply -> false
            SchedulerState.StandardDelay, is SchedulerState.Triggered, is SchedulerState.Retrying -> true
        }

    // Critical-section bound
    private fun scheduleDefault() {
        logger.i { "Scheduling default task with standard interval: ${timings.standardInterval}" }
        schedule(timings.standardInterval, SchedulerState.StandardDelay)
    }

    // Critical section bound
    private fun processSuccess() {
        bufferedTrigger?.let {
            bufferedTrigger = null
            logger.d { "Executing buffered trigger: $it" }
            executeTrigger(it)
        } ?: also { scheduleDefault() }
    }

    // Critical-section bound
    private fun scheduleForFailure(exception: Exception) {
        // If there's a failure, then the whole process will be restarted again, so no need to keep
        // the buffered trigger.
        bufferedTrigger = null
        val state = this.state
        if (state is SchedulerState.Replied) {
            val count = state.original.getRetryCount()
            logger.d { "Task failed, processing retry logic. Original state: ${state.original}, current retry count: $count" }
            if (count < timings.failureRetryingConfig.maximumRetries) {
                val nextCount = count + 1
                val nextTime = (timings.failureRetryingConfig.baseDelay * timings.failureRetryingConfig.multiplier.pow(count))
                logger.d { "Scheduling retry $nextCount/${timings.failureRetryingConfig.maximumRetries} in $nextTime" }
                schedule(nextTime, SchedulerState.Retrying(original = state.original.originalState(),
                    retryNumber = nextCount))
            }
            else {
                logger.i { "Maximum retries (${timings.failureRetryingConfig.maximumRetries}) reached, reporting failure and stopping scheduler" }
                reportFailureAndPause(exception)
            }
        }
    }

    // Critical-section bound
    private fun reportFailureAndPause(exception: Exception) {
        scope.launch {
            reachedMaximumFailureRetries(exception)
        }
        this.state = SchedulerState.Idle
        this.currentJob?.cancel()
        this.currentJob = null
        this.bufferedTrigger = null
        this.expectedExecutionTime = null
    }
    // endregion:
}

private fun SchedulerState.getRetryCount(): Int = when (this) {
    is SchedulerState.Retrying -> this.retryNumber
    else -> 0
}

private fun SchedulerState.originalState(): SchedulerState = when(this) {
    is SchedulerState.Retrying -> original
    is SchedulerState.Replied -> original
    is SchedulerState.WaitingForReply -> original
    is SchedulerState.Triggered, SchedulerState.Idle, SchedulerState.StandardDelay, SchedulerState.Stopped -> this
}

/**
 * Factory function to create a Scheduler with default timings.
 * 
 * @param taskFunction The task to execute. Should throw an exception on failure.
 * @param reachedMaximumFailureRetries Called when max retries are exhausted with the final exception
 * @return A new Scheduler instance configured with default timings
 */
fun createScheduler(
    taskFunction: suspend () -> Unit,
    reachedMaximumFailureRetries: suspend (Exception) -> Unit
): Scheduler {
    return Scheduler(
        timings = DefaultTimings,
        taskFunction = taskFunction,
        reachedMaximumFailureRetries = reachedMaximumFailureRetries
    )
}