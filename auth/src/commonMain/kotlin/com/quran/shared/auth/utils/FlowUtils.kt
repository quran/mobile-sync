package com.quran.shared.auth.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Utility to help Swift/iOS code collect Kotlin Flows.
 */
fun <T> Flow<T>.watch(block: (T) -> Unit): Job {
    return onEach { block(it) }
        .launchIn(CoroutineScope(Dispatchers.Main))
}
