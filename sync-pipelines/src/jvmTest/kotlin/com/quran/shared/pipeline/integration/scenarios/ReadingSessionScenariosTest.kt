package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.knownIssue
import com.quran.shared.pipeline.integration.mutation
import com.quran.shared.pipeline.integration.syncFailedLocally
import com.quran.shared.pipeline.integration.syncRejectedWith
import com.quran.shared.pipeline.integration.syncScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Reading sessions, the reading history. */
class ReadingSessionScenariosTest {

    @Test
    fun `RS-01 adding a session`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")

        phone.data.addReadingSession(2, 255)
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        assertEquals(listOf("2:255"), server.state(ALICE).sessionVerses)
        assertEquals(listOf("2:255"), tablet.sessionVerses())
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `RS-02 revisiting a verse updates the session instead of duplicating it`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.data.addReadingSession(2, 255, at(0))
        syncUntilQuiescent(phone, tablet)

        phone.data.addReadingSession(2, 255, at(60_000))
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        val session = server.state(ALICE).sessions.single()
        assertEquals("2:255", session.verseKey)
        assertEquals(T0 + 60_000, session.clientUpdatedAt)
        assertEquals(listOf("2:255"), tablet.sessionVerses())
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `RS-06 pruning to 20`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        (1..20).forEach { ayah -> phone.data.addReadingSession(2, ayah, at(ayah * 1_000L)) }
        syncUntilQuiescent(phone, tablet)

        phone.data.addReadingSession(3, 1, at(100_000))
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        val expected = ((2..20).map { "2:$it" } + "3:1").sorted()
        assertEquals(expected, server.state(ALICE).sessionVerses, "the oldest, 2:1, is pruned")
        assertEquals(expected, tablet.sessionVerses())
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `RS-08 two pruned never-synced sessions at one verse don't break the first sync`() = syncScenario {
        val phone = device("phone")
        phone.data.addReadingSession(18, 1, at(0))
        (1..20).forEach { phone.data.addReadingSession(2, it, at(it * 1_000L)) }
        phone.data.addReadingSession(18, 1, at(50_000))
        (1..20).forEach { phone.data.addReadingSession(3, it, at(100_000 + it * 1_000L)) }
        web(ALICE).addReadingSession(18, 1)

        val run = phone.login(ALICE)

        knownIssue(
            "S1",
            "Two never-sent session tombstones at one verse make a remote create throw",
            expect = syncFailedLocally("IllegalStateException")
        ) {
            assertSucceeded(run)
        }
    }

    @Test
    fun `RS-09 a pruned never-synced session doesn't delete another device's session`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        tablet.data.addReadingSession(18, 1, at(0))
        assertSucceeded(tablet.syncNow())
        phone.data.addReadingSession(18, 1, at(10))
        (1..20).forEach { phone.data.addReadingSession(2, it, at(1_000L + it)) }

        knownIssue(
            "S1",
            "A never-sent session tombstone binds another device's session and deletes it",
            expect = failureMentions("18:1 on the server")
        ) {
            assertSucceeded(phone.syncNow())
            assertSucceeded(phone.syncNow())
            assertTrue("18:1" in server.state(ALICE).sessionVerses, "18:1 on the server")
        }
    }

    @Test
    fun `RS-10 revisiting a pruned synced session before the next sync keeps the visit`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addReadingSession(2, 255, at(0))
        syncUntilQuiescent(phone)
        (1..20).forEach { phone.data.addReadingSession(3, it, at(1_000L + it)) }

        phone.data.addReadingSession(2, 255, at(5_000))

        knownIssue(
            "S5",
            "A pruned synced session revisited before the next sync is lost on both sides",
            expect = failureMentions("2:255 on the")
        ) {
            assertSucceeded(phone.syncNow())
            assertTrue("2:255" in phone.sessionVerses(), "2:255 on the device")
            assertTrue("2:255" in server.state(ALICE).sessionVerses, "2:255 on the server")
        }
    }

    @Test
    fun `RS-11 revisiting a session another device deleted`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.data.addReadingSession(2, 255, at(0))
        syncUntilQuiescent(phone, tablet)
        tablet.data.deleteReadingSession(2, 255)
        assertSucceeded(tablet.syncNow())

        // Deletes take no timestamp and use the wall clock, so the later visit is stamped after it.
        phone.data.addReadingSession(2, 255, Instant.fromEpochMilliseconds(System.currentTimeMillis() + 60_000))

        knownIssue(
            "S8",
            "Revisiting a session another device deleted pushes an UPDATE that gets 404 forever",
            expect = syncRejectedWith(404, carrying = mutation("READING_SESSION", "UPDATE"))
        ) {
            assertSucceeded(phone.syncNow())
            assertEquals(listOf("2:255"), phone.sessionVerses())
            assertEquals(listOf("2:255"), server.state(ALICE).sessionVerses)
        }
    }

    @Test
    fun `RS-03 moving a session`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        val session = phone.data.addReadingSession(2, 255)
        syncUntilQuiescent(phone, tablet)

        phone.data.updateReadingSession(session.id, 3, 7)
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        assertEquals(listOf("3:7"), server.state(ALICE).sessionVerses)
        listOf(phone, tablet).forEach { assertEquals(listOf("3:7"), it.sessionVerses(), it.name) }
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `RS-04 moving onto an existing session merges them`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        val moved = phone.data.addReadingSession(2, 255, at(0))
        phone.data.addReadingSession(3, 7, at(1_000))
        syncUntilQuiescent(phone, tablet)

        phone.data.updateReadingSession(moved.id, 3, 7, at(2_000))
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        assertEquals(listOf("3:7"), server.state(ALICE).sessionVerses)
        listOf(phone, tablet).forEach { assertEquals(listOf("3:7"), it.sessionVerses(), it.name) }
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `RS-05 deleting a session`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.data.addReadingSession(2, 255)
        phone.data.addReadingSession(18, 1)
        syncUntilQuiescent(phone, tablet)

        assertTrue(phone.data.deleteReadingSession(2, 255))
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        assertEquals(listOf("18:1"), server.state(ALICE).sessionVerses)
        listOf(phone, tablet).forEach { assertEquals(listOf("18:1"), it.sessionVerses(), it.name) }
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `RS-07 two devices' sessions converge on the same newest 20`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        // Interleaved times: the phone's visits are even seconds, the tablet's odd ones.
        (1..15).forEach { i ->
            phone.data.addReadingSession(2, i, at(2_000L * i))
            tablet.data.addReadingSession(3, i, at(2_000L * i + 1_000))
        }

        syncUntilQuiescent(phone, tablet, maxRounds = 6)

        val newest = ((6..15).map { "2:$it" } + (6..15).map { "3:$it" }).sorted()
        assertEquals(newest, server.state(ALICE).sessionVerses)
        listOf(phone, tablet).forEach { assertEquals(newest, it.sessionVerses(), it.name) }
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `RS-12 both devices delete the same session`() = syncScenario {
        // Deletes take no timestamp, so the devices stamp them with the wall clock. The server's
        // clock stands an hour back, so every server change, the tablet's delete included, is
        // stamped then plus 1 ms per change (the backend's monotonic bump). The phone's later delete
        // is newer for sure.
        server.clock.frozenAt = System.currentTimeMillis() - 3_600_000
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.data.addReadingSession(2, 255)
        phone.data.addReadingSession(18, 1)
        syncUntilQuiescent(phone, tablet)

        tablet.data.deleteReadingSession(2, 255)
        assertSucceeded(tablet.syncNow())
        phone.data.deleteReadingSession(2, 255)

        knownIssue(
            "S8",
            "A later local delete of a session another device deleted gets 404 forever",
            expect = syncRejectedWith(404, carrying = mutation("READING_SESSION", "DELETE"))
        ) {
            assertSucceeded(phone.syncNow())
            assertEquals(listOf("18:1"), server.state(ALICE).sessionVerses)
            listOf(phone, tablet).forEach { assertEquals(listOf("18:1"), it.sessionVerses(), it.name) }
            assertConverged(ALICE, phone, tablet)
        }
    }

    @Test
    fun `RS-13 a remote move onto a verse that has a local session`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addReadingSession(3, 7, at(0))
        phone.data.addReadingSession(2, 255, at(1_000))
        syncUntilQuiescent(phone)
        val web = web(ALICE)

        // A device merges a move onto a verse it holds into a DELETE plus an UPDATE, so a real
        // move, and the server's DELETE of the session it replaces, comes from the web.
        web.moveReadingSession(web.sessionId(2, 255)!!, 3, 7)
        val run = phone.syncNow()

        assertSucceeded(run)
        assertEquals(0, run.posts)
        assertEquals(listOf("3:7"), phone.sessionVerses())
        assertConverged(ALICE, phone)
    }

    @Test
    fun `RS-13 variant, a remote move onto a verse with a pending local revisit`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addReadingSession(3, 7, at(0))
        phone.data.addReadingSession(2, 255, at(1_000))
        syncUntilQuiescent(phone)
        val web = web(ALICE)

        // The revisit makes the phone's 3:7 pending, so the remote DELETE of that session is not
        // simply applied, and the moved session's UPDATE lands on a verse the phone still holds.
        phone.data.addReadingSession(3, 7, Instant.fromEpochMilliseconds(System.currentTimeMillis() + 60_000))
        web.moveReadingSession(web.sessionId(2, 255)!!, 3, 7)

        // Today the newer revisit wins last-write-wins over the remote DELETE and its UPDATE gets
        // 404 (S8), so the POST fails before the move is applied. Once S8 is fixed, applying the
        // move onto the phone's 3:7 may hit the unique position index (S19).
        knownIssue(
            "The revisit's UPDATE gets 404 (S8); behind it, the move may violate the unique index (S19)",
            "S8" to syncRejectedWith(404, carrying = mutation("READING_SESSION", "UPDATE")),
            "S19" to syncFailedLocally("SQLiteException")
        ) {
            assertSucceeded(phone.syncNow())
            syncUntilQuiescent(phone)
            assertEquals(listOf("3:7"), phone.sessionVerses())
            assertEquals(listOf("3:7"), server.state(ALICE).sessionVerses)
            assertConverged(ALICE, phone)
        }
    }
}
