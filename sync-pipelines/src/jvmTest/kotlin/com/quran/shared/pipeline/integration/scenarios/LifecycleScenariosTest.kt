package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.pipeline.LogoutWarning
import com.quran.shared.pipeline.integration.DeviceTimings
import com.quran.shared.pipeline.integration.SyncDevice
import com.quran.shared.pipeline.integration.SyncScenario
import com.quran.shared.pipeline.integration.UserDataSnapshot
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.knownIssue
import com.quran.shared.pipeline.integration.server.ClientTimes
import com.quran.shared.pipeline.integration.server.CollectionRules
import com.quran.shared.pipeline.integration.syncScenario
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Catalog section 2.1: login, logout, account switches and relaunches. */
class LifecycleScenariosTest {

    @Test
    fun `LIFE-01 first login downloads existing server data`() = syncScenario {
        val web = web(ALICE)
        web.createNote("Patience is light", "2:153-2:153")
        web.createNote("Stand in the night", "73:1-73:4")
        val study = web.createCollection("Study")
        web.addToCollection(study, 3, 18)
        web.addToCollection(study, 2, 255)
        web.addBookmark(1, 1)
        web.highlight(2, 255, "blue")
        web.highlight(4, 1, "green")
        listOf(18 to 1, 36 to 1, 67 to 1).forEach { (sura, ayah) -> web.addReadingSession(sura, ayah) }
        web.setAyahReadingBookmark(1, 18, 10)
        web.setPageReadingBookmark(2, 300)
        val phone = device("phone")

        val run = phone.login(ALICE)

        assertSucceeded(run)
        assertEquals(0, run.posts)
        assertConverged(ALICE, phone)
        assertEquals(2, phone.data.notes.first().size)
        assertEquals(listOf("1:1"), phone.favoriteVerses())
        assertEquals(listOf("2:255 blue", "4:1 green"), phone.highlightList())
    }

    @Test
    fun `LIFE-02 first login uploads existing local data`() = syncScenario {
        val phone = device("phone")
        phone.writeOneOfEach()

        val run = phone.login(ALICE)

        assertSucceeded(run)
        val server = server.state(ALICE)
        assertEquals(listOf("Q&amp;A on &lt;patience&gt;"), server.notes.map { it.body })
        assertEquals(setOf("1:1"), server.favorites)
        assertTrue(
            run.accepted.of("COLLECTION_BOOKMARK", "CREATE").any { it.data("collectionId") == "__default__" },
            "Favorites went up as a __default__ membership"
        )
        assertConverged(ALICE, phone)
        val second = phone.syncNow()
        assertSucceeded(second)
        assertEquals(0, second.posts)
    }

    @Test
    fun `LIFE-03 first login merges disjoint local and server data`() = syncScenario {
        val web = web(ALICE)
        web.createNote("Written on the web", "2:255-2:255")
        web.addToCollection(web.createCollection("Remote"), 4, 1)
        web.addReadingSession(36, 1)
        val phone = device("phone")
        phone.data.addNote("Written on the phone", 3, 18, 3, 18)
        phone.data.addAyahBookmarkToCollection(phone.data.addCollection("Local").id, 5, 3)
        phone.data.addReadingSession(67, 1)

        assertSucceeded(phone.login(ALICE))
        syncUntilQuiescent(phone)

        assertEquals(listOf("Written on the phone", "Written on the web"), phone.noteBodies())
        assertEquals(listOf("Local", "Remote"), phone.collectionNames())
        assertEquals(listOf("36:1", "67:1"), phone.sessionVerses())
        assertConverged(ALICE, phone)
    }

    @Test
    fun `LIFE-04 first login links overlapping natural keys`() = syncScenario {
        val web = web(ALICE)
        val older = ClientTimes(T0 + 1_000, T0 + 1_000)
        web.createCollection(" study ", older)
        web.addBookmark(2, 255, times = older)
        web.highlight(2, 255, "blue")
        web.addReadingSession(18, 1, older)
        web.setAyahReadingBookmark(1, 36, 1, times = older)
        val phone = device("phone")
        phone.data.addCollection("Study", at(2_000))
        phone.data.addAyahBookmarkToCollection(phone.favoritesId(), 2, 255, at(2_000))
        phone.data.setHighlight(2, 255, AyahHighlightColor.BLUE, at(2_000))
        phone.data.addReadingSession(18, 1, at(5_000))
        phone.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 18, 10, at(5_000))

        assertSucceeded(phone.login(ALICE))
        syncUntilQuiescent(phone)

        val state = server.state(ALICE)
        assertEquals(1, state.collections.count { CollectionRules.nameKey(it.name) == "study" })
        assertEquals(1, state.bookmarks.count { it.type == "ayah" && it.key == 2 && it.verse == 255 })
        assertEquals(setOf("2:255"), state.versesIn(state.collectionNamed(HIGHLIGHTS_BLUE)!!.id))
        val session = state.sessions.single()
        assertEquals("18:1", session.verseKey)
        assertEquals(T0 + 5_000, session.clientUpdatedAt, "the newest visit wins")
        val slotOne = state.readingBookmarks.single { it.slot == 1 }
        assertEquals(18 to 10, slotOne.key to slotOne.verse, "the newest slot 1 wins")
        assertTrue(server.requests("phone").count { it.status == 409 } <= 1, "a 409 was retried more than once")
        assertConverged(ALICE, phone)
    }

    @Test
    fun `LIFE-05 two devices importing the same legacy data don't duplicate`() = syncScenario {
        val phone = device("phone")
        val tablet = device("tablet")
        assertSucceeded(phone.login(ALICE))
        assertSucceeded(tablet.login(ALICE))
        phone.data.importData(IMPORTED)
        tablet.data.importData(IMPORTED)

        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())
        syncUntilQuiescent(phone, tablet)

        assertEquals(IMPORTED.asSnapshot(), UserDataSnapshot.of(server.state(ALICE)), "the server holds the import once")
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `LIFE-06 logout clears local data and pending changes`() = syncScenario {
        val phone = device("phone")
        val before = syncedThenLoggedOut(phone)

        assertEquals(emptyList(), before.logoutWarnings)
        assertEquals(EMPTY_SNAPSHOT, phone.snapshot())
        assertEquals(before.data, UserDataSnapshot.of(server.state(ALICE)), "the pending edits were discarded")
        assertEquals(before.head, server.head(ALICE))
    }

    @Test
    fun `LIFE-07 re-login to the same account restores everything once`() = syncScenario {
        val phone = device("phone")
        val before = syncedThenLoggedOut(phone)

        val run = phone.login(ALICE)

        assertSucceeded(run)
        assertEquals(0, run.posts)
        assertEquals(before.data, phone.snapshot())
        assertConverged(ALICE, phone)
    }

    @Test
    fun `LIFE-09 an expired session then a different account does not leak A's data into B`() = syncScenario {
        web(BOB).createNote("Bob wrote this long ago", "1:1-1:7")
        web(BOB).addBookmark(112, 1)
        val phone = device("phone")
        assertSucceeded(phone.login(ALICE))
        phone.data.addNote("Alice's pending note", 2, 255, 2, 255)
        phone.data.addReadingSession(18, 1)
        phone.auth.expireSession()
        assertTrue(phone.syncNow().skipped, "the expired session skips the sync")

        val run = phone.login(BOB)

        knownIssue(
            "S3",
            "An account switch after an expired session pushes A's data into B",
            expect = failureMentions("A's data reached B")
        ) {
            assertSucceeded(run)
            assertEquals(emptyList(), server.state(BOB).notes.map { it.body }.filter { "Alice" in it }, "A's data reached B")
            assertEquals(emptyList(), server.state(BOB).sessionVerses, "A's data reached B")
            assertEquals(listOf("Bob wrote this long ago"), phone.noteBodies(), "B's history on the device")
            assertConverged(BOB, phone)
        }
    }

    @Test
    fun `LIFE-11 writes while logged out make no HTTP request`() = syncScenario {
        val phone = device("phone", DeviceTimings(localChangeDelay = 50.milliseconds))
        phone.writeOneOfEach()

        // The harness note for LIFE-11: wait past the short debounce, then check nothing was sent.
        // A logged-out run gets empty headers without reaching the auth boundary, so nothing to await.
        delay(500)

        assertEquals(emptyList(), server.requests)
        assertEquals(1, phone.data.notes.first().size)
        assertEquals(listOf("3:18"), phone.versesIn("Study"))
        assertEquals(listOf("1:1"), phone.favoriteVerses())
        assertEquals(listOf("2:255 blue"), phone.highlightList())
        assertEquals(listOf("18:1"), phone.sessionVerses())
        assertEquals(1, phone.data.readingBookmarks.first().size)
    }

    @Test
    fun `LIFE-12 app relaunch resumes cleanly`() = syncScenario {
        val phone = device("phone")
        assertSucceeded(phone.login(ALICE))
        phone.data.addNote("Synced before the relaunch", 1, 1, 1, 7)
        phone.addToFavorites(2, 255)
        syncUntilQuiescent(phone)
        val head = server.head(ALICE)
        phone.data.addNote("Written before the relaunch", 3, 18, 3, 18)
        phone.data.addCollection("Study")
        phone.data.addReadingSession(36, 1)
        val requestsBefore = server.requests("phone").size

        val run = phone.restart()!!

        assertSucceeded(run)
        assertEquals(head.toString(), run.requests.first { it.method == "GET" }.query["mutationsSince"], "the GET resumed from the stored token")
        assertTrue(run.postStatuses.all { it == 200 }, "POST statuses ${run.postStatuses}")
        syncUntilQuiescent(phone)
        // Pushed once across the relaunch sync and every later run.
        val pushed = server.requests("phone").drop(requestsBefore).filter { it.isPost && it.status == 200 }.flatMap { it.mutations }
        assertEquals(
            mapOf<String?, Int>("NOTE" to 1, "COLLECTION" to 1, "READING_SESSION" to 1),
            pushed.groupingBy { it.resource }.eachCount(),
            "mutations pushed after the relaunch: $pushed"
        )
        assertTrue(pushed.all { it.type == "CREATE" }, "only CREATEs: $pushed")
        assertConverged(ALICE, phone)
    }

    private class ServerBefore(val data: UserDataSnapshot, val head: Long, val logoutWarnings: List<LogoutWarning>)

    /** Syncs one of each type, makes unsynced edits, then logs out. Returns the server as it was before the edits. */
    private suspend fun SyncScenario.syncedThenLoggedOut(phone: SyncDevice): ServerBefore {
        assertSucceeded(phone.login(ALICE))
        phone.writeOneOfEach()
        syncUntilQuiescent(phone)
        val data = UserDataSnapshot.of(server.state(ALICE))
        val head = server.head(ALICE)
        phone.data.addNote("Pending at logout time", 3, 7, 3, 7)
        phone.data.addReadingSession(36, 1)
        phone.data.deleteNote(phone.data.notes.first().first { it.body.startsWith("Q&A") }.id)
        return ServerBefore(data, head, phone.logout().warnings)
    }
}
