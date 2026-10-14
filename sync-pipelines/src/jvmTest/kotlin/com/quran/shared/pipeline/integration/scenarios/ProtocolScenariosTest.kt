package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.input.ImportNote
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.server.ClientTimes
import com.quran.shared.pipeline.integration.syncScenario
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Catalog section 2.10, the sync protocol: GET paging, chained POST chunks, and what the client
 * does with what the server sends. [FailureScenariosTest] holds the section's injected failures.
 */
class ProtocolScenariosTest {

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
    fun `ERR-15 the head changes between pages`() = syncScenario {
        val (phone) = signedIn("phone")
        // The harness note for ERR-15: more than 1000 rows, not a page cap, so there is a page 2.
        val web = web(ALICE)
        repeat(1_050) {
            val verse = "2:${it % 286 + 1}"
            web.createNote("Seeded note number $it", "$verse-$verse", times = ClientTimes(createdAt = T0 + it))
        }
        server.faults.betweenPages { web.createNote("Written between pages", "3:18-3:18") }

        val run = phone.syncNow()

        assertSucceeded(run)
        assertTrue(run.gets > 2, "the paging was retried: ${run.gets} GETs")
        assertEquals(1_051, phone.data.notes.first().size)
        assertTrue("Written between pages" in phone.noteBodies())
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

    @Test
    fun `ERR-17 a 409 between chunks doesn't duplicate committed chunks`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.importData(
            PersistenceImportData(
                notes = (0 until 250).map {
                    ImportNote("Imported note number $it", 2, it % 286 + 1, 2, it % 286 + 1, at(it.toLong()), at(it.toLong()))
                }
            )
        )
        var posts = 0
        // beforePost fires on every POST it matches; the web writes only before the second one.
        server.faults.beforePost(times = 2) { if (++posts == 2) web(ALICE).addReadingSession(36, 1) }

        syncUntilQuiescent(phone)

        assertTrue(server.posts("phone").any { it.status == 409 }, "the second chunk got 409")
        assertEquals(250, server.state(ALICE).notes.size)
        assertEquals(listOf("36:1"), phone.sessionVerses())
        assertConverged(ALICE, phone)
    }
}
