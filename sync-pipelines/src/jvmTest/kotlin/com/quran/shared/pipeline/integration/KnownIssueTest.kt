package com.quran.shared.pipeline.integration

import com.quran.shared.pipeline.integration.server.ClientTimes
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

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
            runBlocking { knownIssue("S1", "Example issue") { } }
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
            runBlocking { knownIssue("S1", "Example issue") { throw NullPointerException() } }
        }
        assertFailsWith<HarnessTimeoutException> {
            runBlocking { knownIssue("S1", "Example issue", timeout = 50.milliseconds) { delay(5_000) } }
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
}
