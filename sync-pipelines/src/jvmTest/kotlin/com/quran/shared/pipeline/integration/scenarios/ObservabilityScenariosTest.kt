package com.quran.shared.pipeline.integration.scenarios

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.quran.shared.persistence.TextLengthBound
import com.quran.shared.persistence.TextLengthField
import com.quran.shared.persistence.TextLengthLimitException
import com.quran.shared.pipeline.SyncStatus
import com.quran.shared.pipeline.integration.DeviceTimings
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.server.ServerFaults
import com.quran.shared.pipeline.integration.syncScenario
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * What the public API reports. Held items are not covered: no public API exposes them yet (IMP-06
 * and IMP-07 hold notes only locally).
 */
class ObservabilityScenariosTest {

    @Test
    fun `OBS-01 length-limit errors are typed and detailed`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addNote("A note to edit later", 2, 255, 2, 255)
        val study = phone.data.addCollection("Study")
        syncUntilQuiescent(phone)
        val before = phone.snapshot()

        val failures = listOf(
            assertFailsWith<TextLengthLimitException> { phone.data.addNote("short", 1, 1, 1, 1) },
            assertFailsWith<TextLengthLimitException> { phone.data.addNote("x".repeat(10_001), 1, 1, 1, 1) },
            assertFailsWith<TextLengthLimitException> {
                phone.data.updateNote(phone.note("A note to edit later").id, "<".repeat(2_501))
            },
            assertFailsWith<TextLengthLimitException> { phone.data.addCollection(" ") },
            assertFailsWith<TextLengthLimitException> { phone.data.updateCollection(study.id, "y".repeat(256)) }
        )

        assertEquals(
            listOf(
                listOf(TextLengthField.NOTE_BODY, TextLengthBound.TOO_SHORT, 6, 5),
                listOf(TextLengthField.NOTE_BODY, TextLengthBound.TOO_LONG, 10_000, 10_001),
                listOf(TextLengthField.NOTE_BODY, TextLengthBound.TOO_LONG, 10_000, 10_004),
                listOf(TextLengthField.COLLECTION_NAME, TextLengthBound.TOO_SHORT, 1, 0),
                listOf(TextLengthField.COLLECTION_NAME, TextLengthBound.TOO_LONG, 255, 256)
            ),
            failures.map { listOf(it.field, it.bound, it.limit, it.length) }
        )
        assertEquals(before, phone.snapshot(), "no state change")
        val run = phone.syncNow()
        assertSucceeded(run)
        assertEquals(0, run.posts, "nothing to push")
    }

    @Test
    fun `OBS-02 a permanently failing sync is reported once through the public API`() = syncScenario {
        val (phone) = signedIn("phone")
        // A rejected mutation is held and doesn't fail the run; a POST that always fails still does.
        server.faults.failRequests(times = ServerFaults.ALWAYS, status = 503, code = "ServiceUnavailableError")
        phone.data.addNote("Rejected on every attempt", 2, 255, 2, 255)
        phone.data.addCollection("Study")
        phone.data.addReadingSession(18, 1)
        val statuses = CopyOnWriteArrayList<SyncStatus>()

        val run = coroutineScope {
            val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                phone.data.syncStatus.collect { statuses += it }
            }
            phone.syncNow().also { collector.cancel() }
        }

        // A StateFlow replays its current value, the login's result, to a new collector. It also
        // conflates: equal values are not repeated, so the retries show as one Syncing, and a
        // value replaced before the collector runs would be missed. The collector is unconfined,
        // which in practice sees every change, but this can't prove that no value was ever
        // dropped, only that the flow showed one failure.
        assertIs<SyncStatus.Succeeded>(statuses.first(), "the login's result: $statuses")
        assertEquals(listOf(SyncStatus.Syncing, run.status), statuses.drop(1), "one run, then exactly one failure")
        val error = assertIs<SyncStatus.Failed>(run.status).error
        assertEquals(503 to "ServiceUnavailableError", error.httpStatus to error.serverCode)
    }

    @Test
    fun `OBS-03 a successful sync status is observable`() = syncScenario {
        val (phone) = signedIn("phone")
        val afterLogin = assertIs<SyncStatus.Succeeded>(phone.data.syncStatus.value, "the login's sync").at
        phone.data.addNote("Synced and reported", 2, 255, 2, 255)
        val started = Clock.System.now()

        val run = phone.syncNow()

        assertSucceeded(run)
        val status = assertIs<SyncStatus.Succeeded>(phone.data.syncStatus.value)
        assertEquals(run.status, status, "the public status is the run's result")
        assertTrue(status.at >= started && status.at > afterLogin, "last synced moved from $afterLogin to ${status.at}")
        assertEquals(listOf("Synced and reported"), server.state(ALICE).notes.map { it.body })
        assertConverged(ALICE, phone)
    }

    @Test
    fun `OBS-04 logs never carry note bodies, collection names or server messages`() = syncScenario {
        // The host's sink sees every line, a logged error's stack trace included, and may forward it to a crash reporter.
        val lines = CopyOnWriteArrayList<String>()
        val writer = object : LogWriter() {
            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                lines += listOfNotNull(message, throwable?.stackTraceToString()).joinToString("\n")
            }
        }
        Logger.addLogWriter(writer)
        try {
            // No retries, so each failure is reported as soon as its run ends.
            val phone = device("phone", DeviceTimings(maximumRetries = 0))
            assertSucceeded(phone.login(ALICE))
            web(ALICE).createNote("$SECRET, written on the web", "2:255-2:255")
            server.faults.truncateResponse(after = SECRET)

            assertIs<SyncStatus.Failed>(phone.syncNow().status, "the GET was cut off in the note's body")

            server.faults.rejectMutation(status = 403, code = "ForbiddenError", message = "No access to '$SECRET'") {
                it.resource == "COLLECTION"
            }
            phone.data.addCollection(SECRET)

            val forbidden = assertIs<SyncStatus.Failed>(phone.syncNow().status, "a lone 403 may be the scope: the run fails")
            assertEquals(403 to "ForbiddenError", forbidden.error.httpStatus to forbidden.error.serverCode)
        } finally {
            Logger.setLogWriters(Logger.config.logWriterList - writer)
        }
        assertEquals(
            setOf(
                "Sync failed: java.lang.IllegalStateException: The sync server's response is not valid JSON",
                "Sync failed: request forbidden: HTTP 403 ForbiddenError"
            ),
            lines.filter { it.startsWith("Sync failed:") }.toSet(),
            "both failures were still logged"
        )
        assertEquals(emptyList(), lines.filter { SECRET.lowercase() in it.lowercase() }, "user text or a server message was logged")
    }

    private companion object {
        const val SECRET = "Private prayer for Sara"
    }
}
