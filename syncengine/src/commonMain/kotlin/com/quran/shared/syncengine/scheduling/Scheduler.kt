package com.quran.shared.syncengine.scheduling

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class Trigger {
    APP_START,
    LOCAL_DATA_MODIFIED
}

// In seconds
data class SchedulerTimings(
    val standardInterval: Long,
    val localDataModifiedInterval: Long
)

private val DefaultTimings = SchedulerTimings(
    standardInterval = 30L * 60,
    localDataModifiedInterval = 5L * 60
)

private sealed class SchedulerState {
    data object Initialized: SchedulerState()
    data object RegularWait: SchedulerState()
    data object WaitingForReply: SchedulerState()
}

class Scheduler(
    val timings: SchedulerTimings,
    val taskFunction: suspend () -> Boolean,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private var state: SchedulerState = SchedulerState.Initialized

    private val scope: CoroutineScope = CoroutineScope(dispatcher + SupervisorJob())

    private var currentJob: Job? = null

    fun apply(trigger: Trigger) {
        when(trigger) {
            Trigger.APP_START -> scheduleDefault()
            Trigger.LOCAL_DATA_MODIFIED -> scheduleLocalDataModified()
        }
    }

    fun stop() {
        currentJob?.cancel()
        currentJob = null
    }

    private fun schedule(time: Long) {
        currentJob = scope.launch {
            kotlinx.coroutines.delay(time * 1000)

            state = SchedulerState.WaitingForReply
            val result = taskFunction()
            if (result) {
                scheduleDefault()
            }
            else {
                scheduleForFailure()
            }
        }
    }

    private fun scheduleDefault() {
        state = SchedulerState.RegularWait
        schedule(timings.standardInterval)
    }

    private fun scheduleLocalDataModified() {
        state = SchedulerState.RegularWait
        schedule(timings.localDataModifiedInterval)
    }

    private fun scheduleForFailure() {

    }
}