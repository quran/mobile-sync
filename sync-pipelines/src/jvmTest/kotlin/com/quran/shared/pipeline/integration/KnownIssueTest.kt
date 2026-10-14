package com.quran.shared.pipeline.integration

import com.quran.shared.pipeline.SyncStatus
import com.quran.shared.pipeline.integration.server.ClientTimes
import com.quran.shared.syncengine.SyncError
import com.quran.shared.syncengine.SyncErrorKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class KnownIssueTest {
    @Test
    fun `an expected assertion failure counts as the issue reproducing`() = runBlocking {
        knownIssue("S1", "Example issue", expect = { "wedged" in it.message.orEmpty() }) {
            throw AssertionError("sync wedged")
        }
    }

    @Test
    fun `a passing block fails the test so the wrapper is removed`() {
        val failure = assertFailsWith<AssertionError> {
            runBlocking { knownIssue("S1", "Example issue", expect = { true }) { } }
        }
        assertEquals("S1 appears fixed; remove knownIssue", failure.message)
    }

    @Test
    fun `an assertion the issue does not explain is an error`() {
        val failure = assertFailsWith<AssertionError> {
            runBlocking { knownIssue("S1", "Example issue", expect = { false }) { throw AssertionError("something else") } }
        }
        assertTrue(failure.message!!.contains("not the way the issue does"))
    }

    @Test
    fun `exceptions and hangs are errors, not reproductions`() {
        assertFailsWith<NullPointerException> {
            runBlocking { knownIssue("S1", "Example issue", expect = { true }) { throw NullPointerException() } }
        }
        assertFailsWith<HarnessTimeoutException> {
            runBlocking { knownIssue("S1", "Example issue", expect = { true }, timeout = 50.milliseconds) { delay(5_000) } }
        }
    }

    @Test
    fun `a known issue can cover the final duplicate check`() {
        val duplicates = KnownIssue("S12", "A lost response plus a local edit duplicates notes")
        val times = ClientTimes(createdAt = 1_700_000_000_000L)

        syncScenario(knownDuplicates = duplicates) {
            repeat(2) { web("alice").createNote("The same note twice", "2:255-2:255", times = times) }
        }
        val fixed = assertFailsWith<AssertionError> {
            syncScenario(knownDuplicates = duplicates) { web("alice").createNote("Only one note here", "2:255-2:255") }
        }
        assertEquals("S12 appears fixed; remove knownIssue", fixed.message)
    }

    @Test
    fun `syncFailedLocally pins the exception a run gave up on`() {
        val error = SyncError(SyncErrorKind.UNKNOWN, null, null, "Sync failed unexpectedly (SQLiteException)")
        val failure = SyncRunFailure(SyncRun(SyncStatus.Failed(Instant.fromEpochMilliseconds(0), error), emptyList()), "failed")

        assertTrue(syncFailedLocally("SQLiteException")(failure))
        assertFalse(syncFailedLocally("IllegalStateException")(failure))
        assertFalse(syncFailedLocally("SQLiteException")(AssertionError("Sync failed unexpectedly (SQLiteException)")))
    }
}
