package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.knownIssue
import com.quran.shared.pipeline.integration.syncFailedLocally
import com.quran.shared.pipeline.integration.syncRejectedWith
import com.quran.shared.pipeline.integration.syncScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Catalog section 2.8: reading sessions, the reading history. */
class ReadingSessionScenariosTest {

    @Test
    fun `RS-01 adding a session`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")

        phone.data.addReadingSession(2, 255)
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        assertEquals(listOf(2 to 255), server.state(ALICE).sessions.map { it.chapter to it.verse })
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
        assertEquals(2 to 255, session.chapter to session.verse)
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
        assertEquals(expected, server.state(ALICE).sessions.map { "${it.chapter}:${it.verse}" }.sorted(), "the oldest, 2:1, is pruned")
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
            assertTrue(server.state(ALICE).sessions.any { it.chapter == 18 && it.verse == 1 }, "18:1 on the server")
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
            assertTrue(server.state(ALICE).sessions.any { it.chapter == 2 && it.verse == 255 }, "2:255 on the server")
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
            expect = syncRejectedWith(404)
        ) {
            assertSucceeded(phone.syncNow())
            assertEquals(listOf("2:255"), phone.sessionVerses())
            assertEquals(listOf(2 to 255), server.state(ALICE).sessions.map { it.chapter to it.verse })
        }
    }
}
