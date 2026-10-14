package com.quran.shared.pipeline.integration

import com.quran.shared.auth.model.AuthState
import com.quran.shared.pipeline.LogoutWarningType
import com.quran.shared.pipeline.SyncStatus
import com.quran.shared.pipeline.integration.server.ClientTimes
import com.quran.shared.pipeline.integration.server.ErrorEnvelope
import com.quran.shared.pipeline.integration.server.WebActor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** The harness's less obvious paths, each against today's engine. */
class SyncHarnessPathsTest {

    @Test
    fun `restart resumes the stored session and pushes pending edits once`() = syncScenario {
        val phone = device("phone")
        phone.login(ALICE)
        phone.data.addNote("Synced before the restart", 1, 1, 1, 7)
        assertSucceeded(phone.syncNow())
        phone.data.addReadingSession(2, 255)

        val run = phone.restart()!!

        assertSucceeded(run)
        assertEquals(1, run.posts)
        assertEquals(listOf(2 to 255), server.state(ALICE).sessions.map { it.chapter to it.verse })
        assertConverged(ALICE, phone)
    }

    @Test
    fun `an expired session gives a skipped run that assertSucceeded rejects`() = syncScenario {
        val phone = device("phone")
        assertSucceeded(phone.login(ALICE))
        phone.auth.expireSession()

        val run = phone.syncNow()

        assertTrue(run.skipped)
        assertEquals(emptyList(), run.requests)
        assertFailsWith<SyncRunFailure> { assertSucceeded(run) }
        assertEquals(AuthState.Idle, phone.authService.authState.value)
    }

    @Test
    fun `logout clears local data, keeps the server, and reports a failed revocation`() = syncScenario {
        val phone = device("phone")
        phone.login(ALICE)
        phone.data.addNote("Synced before logout", 1, 1, 1, 7)
        assertSucceeded(phone.syncNow())
        phone.data.addNote("Pending at logout", 1, 2, 1, 2)
        phone.auth.failRemoteLogout()

        val result = phone.logout()

        assertEquals(listOf(LogoutWarningType.REVOKE_TOKEN_FAILED), result.warnings.map { it.type })
        assertEquals(emptyList(), phone.data.notes.first())
        assertEquals(listOf("Synced before logout"), server.state(ALICE).notes.map { it.body })
    }

    @Test
    fun `a lost response is retried without duplicating the note`() = syncScenario {
        val phone = device("phone")
        phone.login(ALICE)
        server.faults.loseResponse { it.carries("NOTE") }

        phone.data.addNote("Response lost on the way", 1, 1, 1, 7)
        val run = phone.syncNow()

        // The only POST got the 504, so the server committed the note before losing the answer;
        // the retry found it in GET and pushed nothing.
        assertSucceeded(run)
        assertEquals(listOf(504), run.postStatuses)
        syncUntilQuiescent(phone)
        assertEquals(1, server.state(ALICE).notes.size)
        assertConverged(ALICE, phone)
    }

    @Test
    fun `a write during a blocked POST is pushed by the follow-up sync`() = syncScenario {
        val phone = device("phone", DeviceTimings(localChangeDelay = 300.milliseconds))
        phone.login(ALICE)
        val gate = server.faults.blockPost()
        phone.data.addNote("Pushed while blocked", 1, 1, 1, 7)

        coroutineScope {
            val first = async { phone.syncNow() }
            withContext(Dispatchers.IO) { gate.awaitArrived() }
            phone.data.addReadingSession(2, 255)
            gate.release()
            assertSucceeded(first.await())
        }
        val followUp = phone.awaitSync()

        assertSucceeded(followUp)
        assertEquals(1, followUp.posts)
        assertEquals(1, server.state(ALICE).sessions.size)
    }

    @Test
    fun `a head change between GET pages is retried`() = syncScenario {
        seedNotes(web(ALICE), 1_000)
        server.faults.betweenPages { web(ALICE).createNote("Written between pages", "3:18-3:18") }
        val phone = device("phone")

        val run = phone.login(ALICE)

        assertSucceeded(run)
        assertEquals(4, run.gets)
        assertEquals(1_001, phone.data.notes.first().size)
    }

    @Test
    fun `pagination reports total and hasMore and the device reads every page`() = syncScenario {
        seedNotes(web(ALICE), 1_100)
        val phone = device("phone")

        val run = phone.login(ALICE)

        assertSucceeded(run)
        val pages = run.requests.filter { it.method == "GET" }
        assertEquals(listOf("1", "2"), pages.map { it.query["page"] })
        val first = Json.parseToJsonElement(pages.first().response!!).jsonObject["data"]!!.jsonObject
        assertEquals(1_101, first["total"]!!.jsonPrimitive.int)
        assertTrue(first["hasMore"]!!.jsonPrimitive.boolean)
        assertEquals(1_100, phone.data.notes.first().size)
        assertConverged(ALICE, phone)
    }

    /** Seeds distinct notes: each has its own creation time, so none look like duplicates. */
    @Test
    fun `a run the lifecycle gate skips fails fast with a clear message`() = syncScenario {
        val phone = device("phone")
        phone.login(ALICE)
        val hold = phone.auth.holdNextLogout()

        coroutineScope {
            val logout = async { phone.logout() }
            hold.reached.await()
            val started = System.nanoTime()
            val failure = assertFailsWith<HarnessTimeoutException> { phone.syncNow() }
            val elapsed = (System.nanoTime() - started).nanoseconds

            assertTrue("lifecycle gate" in failure.message.orEmpty(), failure.message)
            assertTrue(elapsed < SyncStatusRecorder.START_TIMEOUT + 2.seconds, "took $elapsed")
            hold.release.complete(Unit)
            logout.await()
        }
    }

    @Test
    fun `the engine reads the bare backend error envelope`() = syncScenario {
        server.errorEnvelope = ErrorEnvelope.BACKEND
        val phone = device("phone")
        phone.login(ALICE)
        server.faults.rejectMutation(status = 422, code = "ValidationError") { it.resource == "NOTE" }

        phone.data.addNote("Rejected for good", 1, 1, 1, 7)
        val run = phone.syncNow()

        val error = assertIs<SyncStatus.Failed>(run.status).error
        assertEquals(422, error.httpStatus)
        assertEquals("ValidationError", error.serverCode)
        assertEquals(List(6) { 422 }, run.postStatuses)
    }

    private fun seedNotes(web: WebActor, count: Int) = repeat(count) {
        val verse = "2:${it % 286 + 1}"
        web.createNote("Seeded note number $it", "$verse-$verse", times = ClientTimes(createdAt = SEED_TIME + it))
    }

    private companion object {
        const val ALICE = "alice"
        const val SEED_TIME = 1_700_000_000_000L
    }
}
