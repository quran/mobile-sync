package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.input.ImportNote
import com.quran.shared.persistence.input.ImportReadingSession
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.pipeline.integration.UserDataSnapshot
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.server.ClientTimes
import com.quran.shared.pipeline.integration.syncScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Importing legacy data and restoring backups. */
class ImportScenariosTest {

    @Test
    fun `IMP-01 importing while logged in uploads once`() = syncScenario {
        val (phone) = signedIn("phone")

        phone.data.importData(IMPORTED)
        syncUntilQuiescent(phone)

        assertEquals(IMPORTED.asSnapshot(), UserDataSnapshot.of(server.state(ALICE)), "the server holds exactly the import")
        assertConverged(ALICE, phone)
        val creates = server.accepted("phone").filter { it.type == "CREATE" }
        assertEquals(
            mapOf(
                // "Old Page Bookmarks" plus the blue highlight collection.
                "COLLECTION" to 2,
                // Two collection verses plus the blue highlight.
                "COLLECTION_BOOKMARK" to 3,
                "NOTE" to 2,
                "READING_SESSION" to 2,
                "READING_BOOKMARK" to 1
            ),
            creates.groupingBy { it.resource!! }.eachCount(),
            "one CREATE per imported item"
        )
        assertEquals(creates, server.accepted("phone"), "only CREATEs were pushed")
    }

    @Test
    fun `IMP-02 repeated tracked imports are no-ops`() = syncScenario {
        val (phone) = signedIn("phone")
        assertTrue(phone.data.importData(IMPORTED, deleteExisting = false, trackHistory = true).changed)
        syncUntilQuiescent(phone)

        val second = phone.data.importData(IMPORTED, deleteExisting = false, trackHistory = true)

        assertFalse(second.changed)
        assertTrue(second.alreadyProcessed > 0, "alreadyProcessed = ${second.alreadyProcessed}")
        val run = phone.syncNow()
        assertSucceeded(run)
        assertEquals(0, run.posts)
        assertConverged(ALICE, phone)
    }

    @Test
    fun `IMP-03 an import while logged out, then a login against overlapping server data`() = syncScenario {
        // The server already holds half of the import, with the same content and timestamps.
        val web = web(ALICE)
        web.createNote("An imported reflection on mercy", "1:1-1:7", times = ClientTimes(T0 + 120, T0 + 120))
        val old = web.createCollection("Old Page Bookmarks", ClientTimes(T0 + 100, T0 + 100))
        web.addToCollection(old, 2, 255)
        web.addReadingSession(18, 1, ClientTimes(T0 + 140, T0 + 140))
        web.highlight(3, 18, "blue")
        val phone = device("phone")
        phone.data.importData(IMPORTED)

        assertSucceeded(phone.login(ALICE))
        syncUntilQuiescent(phone)

        assertEquals(IMPORTED.asSnapshot(), UserDataSnapshot.of(server.state(ALICE)), "the union, each item once")
        assertConverged(ALICE, phone)
    }

    @Test
    fun `IMP-06 a short legacy note is held, not blocking`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.importData(
            PersistenceImportData(
                notes = listOf(
                    ImportNote("Good", 1, 1, 1, 1, at(0), at(0)),
                    ImportNote("A valid imported note", 2, 255, 2, 255, at(10), at(10))
                ),
                readingSessions = listOf(ImportReadingSession(18, 1, at(20), at(20)))
            )
        )

        val first = phone.syncNow()

        assertSucceeded(first)
        assertEquals(listOf("A valid imported note", "Good"), phone.noteBodies(), "Good is kept locally")
        assertEquals(listOf("A valid imported note"), server.state(ALICE).notes.map { it.body })
        assertEquals(listOf("18:1"), server.state(ALICE).sessionVerses)
        repeat(2) {
            val later = phone.syncNow()
            assertSucceeded(later)
            assertEquals(0, later.posts, "a later sync pushed again")
        }
    }
}
