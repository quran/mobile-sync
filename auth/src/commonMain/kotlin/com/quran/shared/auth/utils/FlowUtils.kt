package com.quran.shared.auth.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * A simple interface to allow Swift to cancel a flow subscription.
 */
interface FlowWatcher {
    fun cancel()
}

/**
 * Utility to help Swift/iOS code collect Kotlin Flows.
 */
fun <T> Flow<T>.watch(block: (T) -> Unit): FlowWatcher {
    val job = onEach { block(it) }
        .launchIn(CoroutineScope(Dispatchers.Main))
    
    return object : FlowWatcher {
        override fun cancel() {
            job.cancel()
        }
    }
}
