package com.quran.shared.pipeline.integration

import com.quran.shared.pipeline.SyncError
import com.quran.shared.pipeline.SyncErrorKind
import com.quran.shared.pipeline.SyncStatus
import com.quran.shared.pipeline.integration.server.ClientTimes
import com.quran.shared.pipeline.integration.server.RequestRecord
import com.quran.shared.pipeline.integration.server.ServerDuplicate
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
        val duplicates = KnownDuplicates("S12", "A lost response plus a local edit duplicates notes") { it.kind == ServerDuplicate.Kind.NOTE }
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
    fun `a known issue's duplicates don't cover other duplicates`() {
        val duplicates = KnownDuplicates("S12", "A lost response plus a local edit duplicates notes") { it.kind == ServerDuplicate.Kind.NOTE }

        val other = assertFailsWith<AssertionError> {
            syncScenario(knownDuplicates = duplicates) {
                web("alice").addReadingSession(18, 1)
                // The web upserts by verse, so a second session at that verse is seeded directly.
                val sessions = server.account("alice").sessions
                sessions["rscopy"] = sessions.values.single().copy(id = "rscopy")
            }
        }
        assertTrue("sessions at 18:1" in other.message.orEmpty(), other.message)
    }

    @Test
    fun `allowed duplicates are skipped, and are not required`() {
        val times = ClientTimes(createdAt = 1_700_000_000_000L)

        syncScenario {
            allowDuplicates("Expected by design in this test") { it.kind == ServerDuplicate.Kind.NOTE }
            repeat(2) { web("alice").createNote("The same note twice", "2:255-2:255", times = times) }
        }
        syncScenario {
            allowDuplicates("Expected by design in this test") { it.kind == ServerDuplicate.Kind.NOTE }
            web("alice").createNote("Only one note here", "2:255-2:255")
        }
        val unexpected = assertFailsWith<AssertionError> {
            syncScenario {
                allowDuplicates("Expected by design in this test") { it.kind == ServerDuplicate.Kind.COLLECTION }
                repeat(2) { web("alice").createNote("The same note twice", "2:255-2:255", times = times) }
            }
        }
        assertTrue("has duplicates" in unexpected.message.orEmpty(), unexpected.message)
    }

    @Test
    fun `syncFailedLocally pins the exception a run gave up on`() {
        val error = SyncError(SyncErrorKind.UNKNOWN, null, null, "Sync failed unexpectedly (SQLiteException)")
        val failure = SyncRunFailure(SyncRun(SyncStatus.Failed(Instant.fromEpochMilliseconds(0), error), emptyList()), "failed")

        assertTrue(syncFailedLocally("SQLiteException")(failure))
        assertFalse(syncFailedLocally("IllegalStateException")(failure))
        assertFalse(syncFailedLocally("SQLiteException")(AssertionError("Sync failed unexpectedly (SQLiteException)")))
    }

    @Test
    fun `syncRejectedWith can require the rejected POST to carry the issue's mutation`() {
        val update = Json.parseToJsonElement(
            """{"mutations":[{"type":"UPDATE","resource":"NOTE","resourceId":"n1","data":{"body":"b","ranges":["1:1-1:1"]}}]}"""
        ).jsonObject
        val error = SyncError(SyncErrorKind.REJECTED, 422, "ValidationError", "The sync server rejected the request (HTTP 422)")
        val post = RequestRecord("POST", "phone", "alice", emptyMap(), update, 422, null)
        val failure = SyncRunFailure(SyncRun(SyncStatus.Failed(Instant.fromEpochMilliseconds(0), error), listOf(post)), "failed")

        assertTrue(syncRejectedWith(422)(failure))
        assertTrue(syncRejectedWith(422, carrying = mutation("NOTE", "UPDATE", "ranges"))(failure))
        assertFalse(syncRejectedWith(422, carrying = mutation("NOTE", "UPDATE", "saveToQR"))(failure))
        assertFalse(syncRejectedWith(422, carrying = mutation("NOTE", "CREATE"))(failure))
        assertFalse(syncRejectedWith(404, carrying = mutation("NOTE", "UPDATE"))(failure))
    }

    @Test
    fun `several issues pin a failure, and the one that matched is reported`() = runBlocking {
        knownIssue(
            "Example issues",
            "S2" to { it.message == "echo" },
            "S7" to { it.message == "422" }
        ) { throw AssertionError("422") }
    }

    @Test
    fun `several issues all appear fixed when the block passes`() {
        val failure = assertFailsWith<AssertionError> {
            runBlocking { knownIssue("Example issues", "S2" to { true }, "S7" to { true }) { } }
        }
        assertEquals("S2, S7 appear fixed; remove knownIssue", failure.message)
    }

    @Test
    fun `a failure none of several issues explains is an error`() {
        val failure = assertFailsWith<AssertionError> {
            runBlocking { knownIssue("Example issues", "S2" to { false }, "S7" to { false }) { throw AssertionError("other") } }
        }
        assertTrue(failure.message!!.startsWith("Known issue S2, S7 (Example issues) failed, but not the way any of them does"))
    }
}
