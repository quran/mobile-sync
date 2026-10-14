package com.quran.shared.pipeline.integration

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Polls [condition] every millisecond until it holds or [timeout] passes, and returns whether it
 * held. Use it when the test then asserts the outcome itself, as a known issue does.
 */
suspend fun pollUntil(timeout: Duration = 10.seconds, condition: suspend () -> Boolean): Boolean =
    withTimeoutOrNull(timeout) {
        while (!condition()) delay(1)
        true
    } ?: false

/** Waits until [condition] holds; when it never does, a [HarnessTimeoutException] naming [what]. */
suspend fun awaitUntil(what: String, timeout: Duration = 10.seconds, condition: suspend () -> Boolean) {
    if (!pollUntil(timeout, condition)) throw HarnessTimeoutException("$what: not within $timeout")
}
