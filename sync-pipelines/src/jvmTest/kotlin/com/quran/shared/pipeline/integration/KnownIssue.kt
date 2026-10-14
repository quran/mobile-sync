package com.quran.shared.pipeline.integration

import com.quran.shared.pipeline.SyncStatus
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** An open sync issue, by its tracker id (for example "S6") and a one-line description. */
data class KnownIssue(val id: String, val description: String) {
    init {
        require(id.isNotBlank()) { "A known issue needs an id" }
        require(description.isNotBlank() && '\n' !in description) { "Describe known issue $id in one line" }
    }
}

/**
 * A strict expected failure for an open issue.
 *
 * [block] asserts the *correct* behavior. While the issue is open it fails with an
 * [AssertionError] that [expect] accepts, and the test passes. Once the issue is fixed the block
 * passes, and the test fails with "S6 appears fixed; remove knownIssue".
 *
 * Anything else fails the test as an error: an assertion [expect] rejects, any other exception
 * (harness misuse, a bug in the fake server, a [HarnessTimeoutException]), or a block that runs
 * longer than [timeout]. Pin the failure with [expect] where the issue has a signature, for
 * example `expect = syncRejectedWith(422)`.
 */
suspend fun knownIssue(
    id: String,
    description: String,
    expect: (AssertionError) -> Boolean = { true },
    timeout: Duration = 10.seconds,
    block: suspend () -> Unit
) {
    val issue = KnownIssue(id, description)
    val failure = try {
        withTimeout(timeout) { block() }
        null
    } catch (timeout: TimeoutCancellationException) {
        throw HarnessTimeoutException("Known issue $id: the block did not finish within $timeout", timeout)
    } catch (failure: AssertionError) {
        failure
    }
    if (failure == null) fail("$id appears fixed; remove knownIssue")
    if (!expect(failure)) {
        throw AssertionError("Known issue $id (${issue.description}) failed, but not the way the issue does: ${failure.message}", failure)
    }
    println("Known issue $id still reproduces: ${failure.message?.lineSequence()?.firstOrNull()}")
}

/** Accepts a sync run that failed for good with HTTP [status], as [assertSucceeded] reports it. */
fun syncRejectedWith(status: Int): (AssertionError) -> Boolean = { failure ->
    val run = (failure as? SyncRunFailure)?.run
    (run?.status as? SyncStatus.Failed)?.error?.httpStatus == status
}

/** The harness itself gave up waiting. It is not an assertion, so [knownIssue] never accepts it. */
class HarnessTimeoutException(message: String, cause: Throwable? = null) : Exception(message, cause)
