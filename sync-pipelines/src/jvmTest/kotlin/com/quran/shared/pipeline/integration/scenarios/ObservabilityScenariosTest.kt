package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.TextLengthBound
import com.quran.shared.persistence.TextLengthField
import com.quran.shared.persistence.TextLengthLimitException
import com.quran.shared.pipeline.SyncStatus
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.server.text
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

/** Catalog section 2.12: what the public API reports. */
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
                phone.data.updateNote(phone.note("A note to edit later").id, "<".repeat(2_501), 2, 255, 2, 255)
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
        server.faults.rejectMutation { it.resource == "NOTE" && it.data?.text("body") == "Rejected on every attempt" }
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
        assertEquals(422 to "ValidationError", error.httpStatus to error.serverCode)
    }
}
