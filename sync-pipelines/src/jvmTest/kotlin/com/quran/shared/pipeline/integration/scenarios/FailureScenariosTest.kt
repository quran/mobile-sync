package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.input.ImportNote
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.knownIssue
import com.quran.shared.pipeline.integration.mutation
import com.quran.shared.pipeline.integration.server.ClientTimes
import com.quran.shared.pipeline.integration.server.text
import com.quran.shared.pipeline.integration.syncRejectedWith
import com.quran.shared.pipeline.integration.syncScenario
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Catalog section 2.10: failures and the sync protocol. */
class FailureScenariosTest {

    @Test
    fun `ERR-01 a 409 out of sync refetches and pushes again`() = syncScenario {
        val (phone) = signedIn("phone")
        server.faults.beforePost { web(ALICE).createNote("Written during the sync", "3:18-3:18") }

        phone.data.addCollection("Study")
        val run = phone.syncNow()

        assertSucceeded(run)
        assertEquals(1, run.postStatuses.count { it == 409 }, "POST statuses ${run.postStatuses}")
        assertEquals(listOf("Written during the sync"), phone.noteBodies())
        assertNotNull(server.state(ALICE).collectionNamed("Study"))
        assertConverged(ALICE, phone)
    }

    @Test
    fun `ERR-02 a lost response for a collection create`() = syncScenario {
        val (phone) = signedIn("phone")
        server.faults.loseResponse { it.carries("COLLECTION", "CREATE") }
        server.faults.loseResponse { it.carries("COLLECTION_BOOKMARK", "CREATE") }

        phone.data.addAyahBookmarkToCollection(phone.data.addCollection("Study").id, 2, 255)
        syncUntilQuiescent(phone)

        assertEquals(2, server.posts("phone").count { it.status == 504 }, "both faults fired")
        val state = server.state(ALICE)
        assertEquals(listOf("Study"), state.collections.map { it.name })
        assertEquals(setOf("2:255"), state.versesIn(state.collectionNamed("Study")!!.id))
        assertConverged(ALICE, phone)
    }

    @Test
    fun `ERR-03 a lost response for natural-key creates`() = syncScenario {
        val (phone) = signedIn("phone")
        server.faults.loseResponse { it.carries("COLLECTION_BOOKMARK") }
        server.faults.loseResponse { it.carries("READING_SESSION") }

        phone.addToFavorites(2, 255)
        phone.data.addReadingSession(18, 1)
        phone.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 36, 1)
        syncUntilQuiescent(phone)

        assertEquals(2, server.posts("phone").count { it.status == 504 }, "both faults fired")
        val state = server.state(ALICE)
        assertEquals(setOf("2:255"), state.favorites)
        assertEquals(listOf("18:1"), state.sessionVerses)
        assertEquals(listOf(1), state.readingBookmarks.map { it.slot })
        assertConverged(ALICE, phone)
    }

    @Test
    fun `ERR-07 one poison mutation doesn't block the rest`() = syncScenario {
        val (phone) = signedIn("phone")
        server.faults.rejectMutation { it.resource == "NOTE" && it.data?.text("body") == POISON }

        phone.data.addNote(POISON, 2, 255, 2, 255)
        phone.data.addCollection("Study")
        phone.data.addReadingSession(18, 1)
        phone.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 36, 1)
        val run = phone.syncNow()

        knownIssue("S11", "One rejected mutation blocks the whole batch forever", expect = syncRejectedWith(422, carrying = mutation("NOTE", "CREATE"))) {
            assertSucceeded(run)
            val state = server.state(ALICE)
            assertNotNull(state.collectionNamed("Study"))
            assertEquals(listOf("18:1"), state.sessionVerses)
            assertEquals(listOf(1), state.readingBookmarks.map { it.slot })
            assertEquals(emptyList(), state.notes)
            assertEquals(listOf(POISON), phone.noteBodies(), "the poison note is held locally")
            assertSucceeded(phone.syncNow())
        }
    }

    @Test
    fun `ERR-09 a transient 500`() = syncScenario {
        val (phone) = signedIn("phone")
        server.faults.failRequests(times = 1, status = 500)

        phone.data.addNote("Retried after a 500", 1, 1, 1, 7)
        val run = phone.syncNow()

        assertSucceeded(run)
        assertEquals(500, run.postStatuses.first(), "the first POST failed")
        assertEquals(200, run.postStatuses.last(), "the last POST succeeded")
        assertEquals(1, server.state(ALICE).notes.size)
        assertConverged(ALICE, phone)
    }

    @Test
    fun `ERR-11 a short network error recovers within retries`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addNote("Sent after the outage", 1, 1, 1, 7)
        // The harness note for ERR-11: a short outage is a number of failed requests, not a time.
        // The network comes back once two requests were dropped, well within the retries.
        server.faults.networkDown()
        val run = coroutineScope {
            val sync = async { phone.syncNow() }
            withTimeout(10.seconds) { while (server.requests("phone").count { it.status == DROPPED } < 2) delay(1) }
            server.faults.networkUp()
            sync.await()
        }

        assertSucceeded(run)
        assertTrue(run.requests.count { it.status == DROPPED } >= 2, "dropped requests: ${run.requests.count { it.status == DROPPED }}")
        assertEquals(1, server.state(ALICE).notes.size)
        assertConverged(ALICE, phone)
    }

    @Test
    fun `ERR-14 pagination includes the virtual Favorites slot`() = syncScenario {
        // The harness note for ERR-14: page with more than 1000 rows, not a page cap. 995 notes come
        // first, so the page boundary falls among the other types seeded after them.
        val web = web(ALICE)
        repeat(995) {
            val verse = "2:${it % 286 + 1}"
            web.createNote("Seeded note number $it", "$verse-$verse", times = ClientTimes(createdAt = T0 + it))
        }
        val study = web.createCollection("Study")
        web.addToCollection(study, 3, 18)
        web.addToCollection(study, 3, 19)
        web.addBookmark(1, 1)
        web.addBookmark(1, 2)
        web.highlight(2, 255, "blue")
        web.highlight(4, 1, "green")
        listOf(18 to 1, 36 to 1, 67 to 1).forEach { (sura, ayah) -> web.addReadingSession(sura, ayah) }
        web.setAyahReadingBookmark(1, 18, 10)
        web.setPageReadingBookmark(2, 300)
        val phone = device("phone")

        val run = phone.login(ALICE)

        assertSucceeded(run)
        val pages = run.requests.filter { it.method == "GET" }
        assertTrue(pages.size >= 2, "GET pages: ${pages.size}")
        assertEquals((1..pages.size).map { it.toString() }, pages.map { it.query["page"] })
        val total = Json.parseToJsonElement(pages.first().response!!).jsonObject["data"]!!.jsonObject["total"]!!.jsonPrimitive.int
        assertEquals(server.state(ALICE).log.size + 1, total, "total counts Favorites")
        assertEquals(995, phone.data.notes.first().size)
        assertConverged(ALICE, phone)
    }

    @Test
    fun `ERR-16 more than 100 local mutations`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.importData(
            PersistenceImportData(
                notes = (0 until 250).map {
                    ImportNote("Imported note number $it", 2, it % 286 + 1, 2, it % 286 + 1, at(it.toLong()), at(it.toLong()))
                }
            )
        )

        val run = phone.syncNow()

        assertSucceeded(run)
        val posts = run.requests.filter { it.isPost }
        assertTrue(posts.size >= 3, "POSTs: ${posts.size}")
        assertTrue(posts.all { it.status == 200 }, "POST statuses ${run.postStatuses}")
        posts.zipWithNext().forEach { (previous, next) ->
            assertEquals(previous.responseHead().toString(), next.query["lastMutationAt"], "each POST chains the previous head")
        }
        assertEquals(250, server.state(ALICE).notes.size)
        assertConverged(ALICE, phone)
    }

    private companion object {
        const val POISON = "A note the server always rejects"

        /** [com.quran.shared.pipeline.integration.server.RequestRecord.status] of a dropped connection. */
        const val DROPPED = -1
    }
}
