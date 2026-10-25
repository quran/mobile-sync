package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.input.ImportNote
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.pipeline.SyncStatus
import com.quran.shared.pipeline.integration.DeviceTimings
import com.quran.shared.pipeline.integration.SyncRun
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.server.ClientTimes
import com.quran.shared.pipeline.integration.server.ServerFaults
import com.quran.shared.pipeline.integration.syncScenario
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The sync protocol: GET paging, chained POST chunks, and what the client does with what the
 * server sends. [FailureScenariosTest] holds the injected failures.
 */
class ProtocolScenariosTest {

    @Test
    fun `ERR-13 a malformed response`() = syncScenario {
        // No retries, so each malformed answer ends its run, and the test can see what it left behind.
        val phone = device("phone", DeviceTimings(maximumRetries = 0))
        assertSucceeded(phone.login(ALICE))
        // More than 1000 rows, not a page cap, so the GET has a page 2 that can break after page 1
        // arrived.
        val web = web(ALICE)
        repeat(1_050) {
            val verse = "2:${it % 286 + 1}"
            web.createNote("Seeded note number $it", "$verse-$verse", times = ClientTimes(createdAt = T0 + it))
        }
        phone.data.addNote("Written on the phone", 3, 18, 3, 18)
        server.faults.malformedResponse(kind = ServerFaults.Malformed.INVALID_JSON) { !it.isPost && it.query["page"] == "2" }

        val brokenPage = phone.syncNow()

        assertIs<SyncStatus.Failed>(brokenPage.status)
        assertEquals(listOf("1", "2"), brokenPage.requests.filter { it.method == "GET" }.map { it.query["page"] })
        assertEquals(listOf(INVALID_JSON), brokenPage.malformed(), "page 2 was invalid JSON")
        assertEquals(0, brokenPage.posts)
        assertEquals(listOf("Written on the phone"), phone.noteBodies(), "nothing from page 1 was applied")

        server.faults.malformedResponse(kind = ServerFaults.Malformed.SUCCESS_FALSE_200) { it.isPost }
        val brokenPost = phone.syncNow()

        assertIs<SyncStatus.Failed>(brokenPost.status)
        assertEquals(listOf(SUCCESS_FALSE), brokenPost.malformed(), "the POST got success:false with 200")
        assertEquals(1_050, server.state(ALICE).notes.size, "the server applied nothing")
        // The fetch is merged before the push: the server's notes arrived, and the phone's note waits to be pushed.
        assertEquals(1_051, phone.noteBodies().size, "the GET was merged")
        assertTrue("Written on the phone" in phone.noteBodies())

        val healed = phone.syncNow()

        assertSucceeded(healed)
        assertEquals(1, server.state(ALICE).notes.count { it.body == "Written on the phone" }, "the phone's note, once")
        assertEquals(1_051, phone.data.notes.first().size)
        assertConverged(ALICE, phone)
    }

    @Test
    fun `ERR-14 pagination includes the virtual Favorites slot`() = syncScenario {
        // More than 1000 rows, not a page cap. 995 notes come first, so the page boundary falls
        // among the other types seeded after them.
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
        assertEquals(null, pages.first().query["lastMutationAt"])
        assertTrue(pages.drop(1).all { it.query["lastMutationAt"] == pages.first().responseHead().toString() })
        assertEquals(setOf("0"), pages.map { it.query["mutationsSince"] }.toSet())
        assertEquals(setOf("1000"), pages.map { it.query["limit"] }.toSet())
        val total = Json.parseToJsonElement(pages.first().response!!).jsonObject["data"]!!.jsonObject["total"]!!.jsonPrimitive.int
        assertEquals(server.state(ALICE).log.size + 1, total, "total counts Favorites")
        assertEquals(995, phone.data.notes.first().size)
        assertConverged(ALICE, phone)
    }

    @Test
    fun `ERR-15 the head changes between pages`() = syncScenario {
        val (phone) = signedIn("phone")
        // More than 1000 rows, not a page cap, so there is a page 2.
        val web = web(ALICE)
        repeat(1_050) {
            val verse = "2:${it % 286 + 1}"
            web.createNote("Seeded note number $it", "$verse-$verse", times = ClientTimes(createdAt = T0 + it))
        }
        server.faults.betweenPages { web.createNote("Written between pages", "3:18-3:18") }

        val run = phone.syncNow()

        assertSucceeded(run)
        val pages = run.requests.filter { it.method == "GET" }
        assertEquals(listOf(200, 409, 200, 200), pages.map { it.status })
        assertEquals(listOf("1", "2", "1", "2"), pages.map { it.query["page"] })
        assertEquals(1, pages.map { it.query["mutationsSince"] }.distinct().size)
        assertEquals(null, pages[2].query["lastMutationAt"], "the restarted page one has no stale head")
        assertEquals(1_051, phone.data.notes.first().size)
        assertTrue("Written between pages" in phone.noteBodies())
        assertConverged(ALICE, phone)
    }

    @Test
    fun `ERR-21 exhausted pinned pulls preserve data and the original watermark for recovery`() = syncScenario {
        val web = web(ALICE)
        web.createNote("Already synced", "1:1-1:1")
        val phone = device("phone", DeviceTimings(maximumRetries = 0))
        val baseline = phone.login(ALICE)
        assertSucceeded(baseline)
        val previousHead = baseline.requests.last { it.method == "GET" }.responseHead().toString()
        repeat(3) { web.createNote("Waiting note $it", "2:1-2:1", times = ClientTimes(createdAt = T0 + it)) }
        server.faults.pageSizeCap = 2
        var writes = 0
        server.faults.betweenPages(times = 3) {
            web.createNote("Concurrent note ${++writes}", "3:18-3:18", times = ClientTimes(createdAt = T0 + 100 + writes))
        }

        val failed = phone.syncNow()

        assertIs<SyncStatus.Failed>(failed.status)
        assertEquals(listOf(200, 409, 200, 409, 200, 409), failed.requests.map { it.status })
        assertEquals(setOf(previousHead), failed.requests.map { it.query["mutationsSince"] }.toSet())
        assertEquals(0, failed.posts)
        assertEquals(listOf("Already synced"), phone.noteBodies(), "no partial page was applied")

        val recovered = phone.syncNow()

        assertSucceeded(recovered)
        assertEquals(setOf(previousHead), recovered.requests.filter { it.method == "GET" }.map { it.query["mutationsSince"] }.toSet())
        assertEquals(7, phone.noteBodies().size)
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

    @Test
    fun `ERR-19 a vanished-resource entry for every type`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addAyahBookmarkToCollection(phone.data.addCollection("Study").id, 2, 255)
        phone.data.addReadingSession(18, 1, at(0))
        phone.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 36, 1, at(0))
        syncUntilQuiescent(phone)
        val before = phone.snapshot()
        val web = web(ALICE)
        val study = web.collectionId("Study")!!
        val session = web.sessionId(18, 1)!!
        val slotOne = server.state(ALICE).readingBookmarks.single { it.slot == 1 }.id
        web.renameCollection(study, "Memorize")
        val membership = web.addToCollection(study, 3, 18)
        web.addReadingSession(18, 1, ClientTimes(T0, T0 + 1_000))
        web.setAyahReadingBookmark(1, 67, 1, times = ClientTimes(T0, T0 + 1_000))
        listOf("COLLECTION" to study, "COLLECTION_BOOKMARK" to membership, "READING_SESSION" to session, "READING_BOOKMARK" to slotOne)
            .forEach { (resource, id) -> server.faults.vanishOnce(resource, id) }

        val first = phone.syncNow()

        assertSucceeded(first)
        assertEquals(3, first.received.count { it["data"] == JsonObject(emptyMap()) }, "the GET sent three {} entries")
        val keyOnly = first.received.of("COLLECTION_BOOKMARK").single()
        assertEquals(setOf("collectionId", "bookmarkId"), (keyOnly["data"] as JsonObject).keys, "the membership came as its key alone")
        assertEquals(0, first.posts, "no wrong deletes pushed")
        val after = phone.snapshot()
        // The collection's and the session's {} are ignored. The key-only membership, with no
        // timestamps, reads like any other membership: the phone applies it, with 3:18 from the
        // BOOKMARK CREATE in the same GET, in the collection it still calls Study.
        assertEquals(
            before.copy(collections = listOf("Study: 2:255,3:18"), readingBookmarks = emptyList()),
            after.copy(readingBookmarks = emptyList()),
            "after the vanished entries"
        )
        // A READING_BOOKMARK entry without a slot is skipped, never read as a cleared slot.
        assertEquals(before.readingBookmarks, after.readingBookmarks, "slot 1 unchanged")


        // The fault clears when the server logs each row again; nothing resends a row the client has passed.
        web.renameCollection(study, "Memorize", ClientTimes(updatedAt = T0 + 2_000))
        web.removeFromCollection(study, 3, 18)
        web.addToCollection(study, 3, 18)
        web.addReadingSession(18, 1, ClientTimes(T0, T0 + 2_000))
        web.setAyahReadingBookmark(1, 67, 1, times = ClientTimes(T0, T0 + 2_000))
        val second = phone.syncNow()

        assertSucceeded(second)
        assertEquals(0, second.posts)
        assertEquals(listOf("2:255", "3:18"), phone.versesIn("Memorize"))
        assertConverged(ALICE, phone)
    }

    @Test
    fun `ERR-20 the client never sends two creates for one reading-bookmark slot in one POST`() = syncScenario {
        val (phone) = signedIn("phone")

        phone.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 18, 10, at(0))
        phone.data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 36, 1, at(1_000))
        phone.data.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 300, at(2_000))
        val run = phone.syncNow()

        assertSucceeded(run)
        run.requests.filter { it.isPost }.forEach { post ->
            val slots = post.mutations.of("READING_BOOKMARK").map { it.data("slot") }
            assertEquals(slots.distinct(), slots, "reading bookmark slots in one POST")
        }
        val slot = server.state(ALICE).readingBookmarks.single { it.slot == 1 }
        assertEquals("page" to 300, slot.type to slot.key)
        assertConverged(ALICE, phone)
    }

    private fun SyncRun.malformed(): List<String> = requests.mapNotNull { it.response?.takeIf { body -> body in MALFORMED } }

    private companion object {
        /** The bodies [ServerFaults.malformedResponse] answers with. */
        const val INVALID_JSON = "{\"success\":true,\"data\":"
        const val SUCCESS_FALSE = "{\"success\":false}"
        val MALFORMED = setOf(INVALID_JSON, SUCCESS_FALSE)
    }
}
