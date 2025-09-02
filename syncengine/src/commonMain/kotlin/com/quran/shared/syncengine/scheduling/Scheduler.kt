package com.quran.shared.syncengine.scheduling

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

enum class Trigger {
    APP_START,
    LOCAL_DATA_MODIFIED,
    IMMEDIATE
}

// In seconds
data class SchedulerTimings(
    val standardInterval: Long,
    val appStartInterval: Long,
    val localDataModifiedInterval: Long
)

private val DefaultTimings = SchedulerTimings(
    appStartInterval = 30L * 60,
    standardInterval = 60L * 60,
    localDataModifiedInterval = 5L * 60
)

private sealed class SchedulerState {
    data object Initialized: SchedulerState()
    data object RegularWait: SchedulerState()
    data object WaitingForReply: SchedulerState()
    data object Replied: SchedulerState()
    data class Triggered(val trigger: Trigger): SchedulerState()
}

@OptIn(ExperimentalTime::class)
class Scheduler(
    val timings: SchedulerTimings,
    val taskFunction: suspend () -> Boolean
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
        val firingTime = currentTime + time * 1000
        if (currentlyScheduled() && firingTime >= (expectedExecutionTime ?: 0)) {
            return
        }
        expectedExecutionTime = firingTime

        currentJob?.cancel()

        this.state = newState
        currentJob = scope.launch {
            kotlinx.coroutines.delay(time * 1000)

            state = SchedulerState.WaitingForReply
            val result = taskFunction()
            state = SchedulerState.Replied
            if (result) {
                scheduleDefault()
            }
            else {
                scheduleForFailure()
            }
        }
    }

    private fun currentlyScheduled(): Boolean =
        when (state) {
            SchedulerState.Initialized, SchedulerState.Replied -> false
            SchedulerState.WaitingForReply -> false // TODO:
            SchedulerState.RegularWait, is SchedulerState.Triggered -> true
        }


    private fun scheduleAppStart() {
        schedule(timings.appStartInterval, SchedulerState.Triggered(Trigger.APP_START))
    }

    private fun scheduleDefault() {
        schedule(timings.standardInterval, SchedulerState.RegularWait)
    }

    private fun scheduleLocalDataModified() {
        schedule(timings.localDataModifiedInterval, SchedulerState.Triggered(Trigger.LOCAL_DATA_MODIFIED))
    }

    private fun scheduleImmediately() {
        schedule(0, SchedulerState.Triggered(Trigger.IMMEDIATE))
    }

    private fun scheduleForFailure() {
        expectedExecutionTime = null
    }
}