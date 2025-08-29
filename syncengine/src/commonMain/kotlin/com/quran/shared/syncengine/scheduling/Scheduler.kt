package com.quran.shared.syncengine.scheduling

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class Trigger {
    APP_START
}

// In seconds
data class SchedulerTimings(val standardInterval: Long/*, val localDataDelay: Long, val maxFailureRetry: Long*/)

private val DefaultTimings = SchedulerTimings(
    standardInterval = 30L * 60
)

private sealed class SchedulerState {
    data object Initialized: SchedulerState()
//    data object Idle: SchedulerState()
    data object RegularWait: SchedulerState()
//    data class Invoked(val trigger: Trigger): SchedulerState()
    data object WaitingForReply: SchedulerState()
//    data class Retrying(val trigger: Trigger): SchedulerState()
}

class Scheduler(
    val timings: SchedulerTimings,
    val taskFunction: suspend () -> Boolean,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private var state: SchedulerState = SchedulerState.Initialized

    private val scope: CoroutineScope = CoroutineScope(dispatcher + SupervisorJob())

    fun apply(trigger: Trigger) {
        val time = when(trigger) {
            Trigger.APP_START -> timings.standardInterval // TODO: Need a new kind of delay.
//            Trigger.FIRST_INVOCATION -> timings.defaultDelay
//            Trigger.LOCAL_MUTATION -> timings.localDataDelay
        }

        val job = scope.launch {
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

    }

    private fun scheduleForFailure() {

    }
}