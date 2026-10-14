package com.quran.shared.pipeline.integration

import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.fail

private val ISSUE_ID = Regex("S([1-9]|1[0-9]|2[0-2])")

/**
 * A strict expected failure for an open issue in `SYNC-ISSUES.md` (S1–S22).
 *
 * [block] asserts the *correct* behavior. While the issue is open it fails, and the test passes.
 * Once the issue is fixed it passes, and the test fails with "S6 appears fixed; remove knownIssue",
 * so the wrapper never outlives the bug.
 */
suspend fun knownIssue(id: String, block: suspend () -> Unit) {
    require(ISSUE_ID.matches(id)) { "Unknown issue id '$id'; use S1-S22 from SYNC-ISSUES.md" }
    val failure = try {
        block()
        null
    } catch (timeout: TimeoutCancellationException) {
        timeout
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        failure
    }
    if (failure == null) fail("$id appears fixed; remove knownIssue")
    println("Known issue $id still reproduces: ${failure::class.simpleName}: ${failure.message?.lineSequence()?.firstOrNull()}")
}
