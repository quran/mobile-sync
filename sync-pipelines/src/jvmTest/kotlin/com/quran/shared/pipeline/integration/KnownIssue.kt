package com.quran.shared.pipeline.integration

import com.quran.shared.pipeline.SyncStatus
import com.quran.shared.pipeline.integration.server.text
import com.quran.shared.syncengine.SyncErrorKind
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
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
 * longer than [timeout]. [expect] is required, so every known issue pins its failure, for example
 * `expect = syncRejectedWith(422)`.
 */
suspend fun knownIssue(
    id: String,
    description: String,
    expect: (AssertionError) -> Boolean,
    timeout: Duration = 20.seconds,
    block: suspend () -> Unit
) {
    val issue = KnownIssue(id, description)
    val failure = try {
        withTimeoutOrNull(timeout) { block() }
            ?: throw HarnessTimeoutException("Known issue $id: the block did not finish within $timeout")
        null
    } catch (failure: AssertionError) {
        failure
    }
    if (failure == null) fail("$id appears fixed; remove knownIssue")
    if (!expect(failure)) {
        throw AssertionError("Known issue $id (${issue.description}) failed, but not the way the issue does: ${failure.message}", failure)
    }
    println("Known issue $id still reproduces: ${failure.message?.lineSequence()?.firstOrNull()}")
}

/**
 * Accepts a sync run that failed for good with HTTP [status], as [assertSucceeded] reports it.
 * With [carrying], the run's last POST answered with [status] must also carry a mutation it
 * accepts (see [mutation]), so the pin names the mutation the issue is about.
 */
fun syncRejectedWith(status: Int, carrying: ((JsonObject) -> Boolean)? = null): (AssertionError) -> Boolean = { failure ->
    val run = (failure as? SyncRunFailure)?.run
    run != null && (run.status as? SyncStatus.Failed)?.error?.httpStatus == status &&
        (carrying == null || run.requests.lastOrNull { it.method == "POST" && it.status == status }?.mutations.orEmpty().any(carrying))
}

/** Matches a POSTed mutation of [resource] and [type] whose `data` has every key in [dataKeys]. */
fun mutation(resource: String, type: String, vararg dataKeys: String): (JsonObject) -> Boolean = { mutation ->
    val data = mutation["data"] as? JsonObject
    mutation.text("resource") == resource && mutation.text("type") == type && dataKeys.all { data?.containsKey(it) == true }
}

/**
 * Accepts a sync run that gave up on a local [exception], named by its simple class name: no HTTP
 * status, kind UNKNOWN. That is how an exception thrown while applying remote changes surfaces
 * through the public status.
 */
fun syncFailedLocally(exception: String): (AssertionError) -> Boolean = { failure ->
    val error = ((failure as? SyncRunFailure)?.run?.status as? SyncStatus.Failed)?.error
    error != null && error.httpStatus == null && error.kind == SyncErrorKind.UNKNOWN && "($exception)" in error.message
}

/** The harness itself gave up waiting. It is not an assertion, so [knownIssue] never accepts it. */
class HarnessTimeoutException(message: String, cause: Throwable? = null) : Exception(message, cause)
