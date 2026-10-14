package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.input.ImportAyahHighlight
import com.quran.shared.persistence.input.ImportCollection
import com.quran.shared.persistence.input.ImportCollectionAyahBookmark
import com.quran.shared.persistence.input.ImportNote
import com.quran.shared.persistence.input.ImportReadingBookmark
import com.quran.shared.persistence.input.ImportReadingSession
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.server.ClientTimes
import com.quran.shared.pipeline.integration.syncScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Catalog section 2.9: importing legacy data and restoring backups. */
class ImportScenariosTest {

    @Test
    fun `IMP-01 importing while logged in uploads once`() = syncScenario {
        val (phone) = signedIn("phone")

        phone.data.importData(EVERY_TYPE)
        syncUntilQuiescent(phone)

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
        assertTrue(phone.data.importData(EVERY_TYPE, deleteExisting = false, trackHistory = true).changed)
        syncUntilQuiescent(phone)

        val second = phone.data.importData(EVERY_TYPE, deleteExisting = false, trackHistory = true)

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
        phone.data.importData(EVERY_TYPE)

        assertSucceeded(phone.login(ALICE))
        syncUntilQuiescent(phone)

        val state = server.state(ALICE)
        assertEquals(2, state.notes.size)
        assertEquals(setOf("2:255", "2:256"), state.versesIn(state.collectionNamed("Old Page Bookmarks")!!.id))
        assertEquals(listOf("18:1", "36:1"), state.sessions.map { "${it.chapter}:${it.verse}" }.sorted())
        assertEquals(setOf("3:18"), state.versesIn(state.collectionNamed("system:highlights:blue")!!.id))
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
        assertEquals(listOf(18 to 1), server.state(ALICE).sessions.map { it.chapter to it.verse })
        repeat(2) {
            val later = phone.syncNow()
            assertSucceeded(later)
            assertEquals(0, later.posts, "a later sync pushed again")
        }
    }

    private companion object {
        val EVERY_TYPE = PersistenceImportData(
            collections = listOf(ImportCollection("Old Page Bookmarks", at(100), at(100))),
            collectionBookmarks = listOf(
                ImportCollectionAyahBookmark("Old Page Bookmarks", 2, 255, at(110), at(110)),
                ImportCollectionAyahBookmark("Old Page Bookmarks", 2, 256, at(111), at(111))
            ),
            notes = listOf(
                ImportNote("An imported reflection on mercy", 1, 1, 1, 7, at(120), at(120)),
                ImportNote("Another imported note on patience", 2, 153, 2, 153, at(121), at(121))
            ),
            highlights = listOf(ImportAyahHighlight(3, 18, AyahHighlightColor.BLUE, at(130), at(130))),
            readingSessions = listOf(ImportReadingSession(18, 1, at(140), at(140)), ImportReadingSession(36, 1, at(150), at(150))),
            readingBookmarks = listOf(ImportReadingBookmark.Ayah(67, 1, at(160), ReadingBookmarkSlot.TEAL))
        )
    }
}
