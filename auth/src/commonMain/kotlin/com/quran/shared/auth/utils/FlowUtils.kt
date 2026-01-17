package com.quran.shared.auth.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * A simple interface to allow Swift to cancel a flow subscription.
 */
interface FlowWatcher {
    fun cancel()
}

/**
 * A wrapper for StateFlow that exposes the generic type T to Swift.
 *
 * This allows specialized access (e.g. .value) without casting in Swift.
 */
class CommonStateFlow<T>(private val flow: StateFlow<T>) : StateFlow<T> by flow {
    
    /**
     * Subscribe to value updates from Swift.
     */
    fun watch(block: (T) -> Unit): FlowWatcher {
        val job = onEach { block(it) }
            .launchIn(CoroutineScope(Dispatchers.Main))
        
        return object : FlowWatcher {
            override fun cancel() {
                job.cancel()
            }
        }
    }
}

/**
 * Extension to convert StateFlow to CommonStateFlow.
 */
fun <T> StateFlow<T>.toCommonStateFlow() = CommonStateFlow(this)

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
