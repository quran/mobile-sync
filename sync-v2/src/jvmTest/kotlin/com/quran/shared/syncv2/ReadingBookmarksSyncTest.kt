package com.quran.shared.syncv2

import com.quran.shared.syncv2.Device.Companion.FAVORITES
import com.quran.shared.syncv2.fake.FakeSyncServer
import com.quran.shared.syncv2.fake.int
import com.quran.shared.syncv2.fake.str
import com.quran.shared.syncv2.types.ReadingLocation
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reading bookmarks: three slots, keyed by slot, merged whole (the newer slot state wins). */
class ReadingBookmarksSyncTest {
    private fun t(n: Int) = 1_760_000_000_000L + n * 1_000L

    private fun FakeSyncServer.lastMutations() = postBodies.last()["mutations"]!!.jsonArray.map { it.jsonObject }

    /**
     * The server keeps only the bookmark group and answers with it and its stand-in mushaf (5 for `verses_6236` and
     * `pages_604`, 7 and 6 for the IndoPak page groups). A slot set with any mushaf is stored as that stand-in, so
     * its echo is a no-op; only `mushafId` is sent.
     */
    @Test
    fun `a slot's mushaf is the group's representative, so its echo is a no-op`() {
        listOf(
            ReadingLocation.ayah(18, 10, mushafId = 1) to 5,
            ReadingLocation.ayah(18, 10, mushafId = 2) to 5,
            ReadingLocation.page(300, mushafId = 1) to 5,
            ReadingLocation.page(300, mushafId = 2) to 5,
            ReadingLocation.page(300, mushafId = 7) to 7,
            ReadingLocation.page(300, mushafId = 6) to 6
        ).forEach { (location, representative) ->
            FakeSyncServer().use { server ->
                Device(server).use { device ->
                    device.readingBookmarks.set(1, location, t(0))
                    assertEquals(representative, device.readingBookmarks.readingBookmarks().single().location!!.mushafId)

                    device.settle()

                    val data = server.lastMutations().single()["data"] as JsonObject
                    assertEquals(representative, data.int("mushafId"), "$location")
                    assertTrue("mushaf" !in data, "the resource accepts only mushafId")
                    assertEquals(1, server.postBodies.size, "$location: the echo pushed nothing")
                    assertEquals(location, device.readingBookmarks.readingBookmarks().single().location)
                }
            }
        }
    }

    /** RB-04: a clear is an UPDATE with every location field null (a DELETE would reset the slot's timestamps). */
    @Test
    fun `clearing a slot is an update with nulls and keeps its name`() = FakeSyncServer().use { server ->
        Device(server).use { phone ->
            Device(server).use { tablet ->
                phone.readingBookmarks.set(1, ReadingLocation.ayah(18, 10), t(0))
                phone.readingBookmarks.rename(1, "Khatm", t(1))
                phone.settle()
                tablet.settle()

                phone.readingBookmarks.clear(1, t(2))
                phone.settle()
                tablet.settle()

                val clear = server.postBodies[1]["mutations"]!!.jsonArray.single().jsonObject
                assertEquals("UPDATE", clear.str("type"))
                val data = clear["data"] as JsonObject
                listOf("mushafId", "type", "group", "key", "verseNumber").forEach { assertEquals(JsonNull, data[it], it) }
                assertEquals(mapOf(1 to "-"), Remote(server).readingSlots())
                assertEquals("Khatm", Remote(server).readingBookmarkName(1))
                assertEquals(mapOf(1 to "-"), tablet.readingSlots())
                assertEquals("Khatm", tablet.readingBookmarks.readingBookmarks().single().name)
                assertTrue(404 !in server.postStatuses)
            }
        }
    }

    /** TXT-11: names are trimmed like the server, and an empty one is null, so nothing is pushed back. */
    @Test
    fun `slot names are trimmed like the server`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.readingBookmarks.rename(1, "  Juz 30  ", t(0))
            device.readingBookmarks.rename(2, "   ", t(1))
            // The repository trims before anything is synced; the server's own trimming can't hide a miss.
            assertEquals(listOf("Juz 30", null), device.readingBookmarks.readingBookmarks().map { it.name })

            device.settle()

            assertEquals(listOf("Juz 30", null), device.readingBookmarks.readingBookmarks().map { it.name })
            assertEquals("Juz 30", Remote(server).readingBookmarkName(1))
            assertNull(Remote(server).readingBookmarkName(2))
            assertEquals(1, server.postBodies.size)
        }
    }

    @Test
    fun `a slot on a verse that doesn't exist is held`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.readingBookmarks.set(1, ReadingLocation.ayah(1, 8), t(0))
            device.readingBookmarks.set(2, ReadingLocation.ayah(1, 7), t(1))

            assertEquals(listOf("INVALID_VERSE"), device.settle().held.map { it.reason })
            assertEquals(mapOf(2 to "1:7"), Remote(server).readingSlots())
        }
    }

    /**
     * The server collapses same-slot creates, so a response can be shorter than its request. Positions then say
     * nothing: the batch is settled from its outbox by the next GET (slots by key and content, notes by
     * `clientCreatedAt` and ranges), and nothing is bound to the wrong item or pushed twice.
     */
    @Test
    fun `a response shorter than its request is settled by identity, not position`() {
        listOf(true, false).forEach { withIndex ->
            FakeSyncServer().use { server ->
                server.includeMutationIndex = withIndex
                Device(server).use { device ->
                    device.readingBookmarks.set(1, ReadingLocation.ayah(18, 10), t(0))
                    device.readingBookmarks.set(2, ReadingLocation.page(300), t(1))
                    device.addNote("Written with the slots", "1:1", t(2))
                    server.dropFromNextResponse = { it.str("resource") == "READING_BOOKMARK" && (it["data"] as JsonObject).int("slot") == 1 }

                    device.settle()
                    assertEquals(1, server.postBodies.size, "index=$withIndex: nothing pushed twice")

                    device.readingBookmarks.set(2, ReadingLocation.page(301), t(3))
                    device.settle()

                    assertEquals(mapOf(1 to "18:10", 2 to "page 301"), Remote(server).readingSlots(), "index=$withIndex")
                    assertEquals(listOf("Written with the slots"), Remote(server).noteBodies())
                    assertEquals(Remote(server).readingBookmarkId(2), server.lastMutations().single().str("resourceId"))
                }
            }
        }
    }

    /**
     * Slot 1 is mirrored onto the legacy BOOKMARK `isReading` flag. That bookmark is outside Favorites, so the verse
     * doesn't appear there; and a Favorites removal keeps a bookmark that is the reading position.
     */
    @Test
    fun `slot 1's reading bookmark stays out of Favorites and survives a Favorites removal`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.readingBookmarks.set(1, ReadingLocation.ayah(2, 255), t(0))
            device.settle()
            assertEquals("2:255", Remote(server).readingVerse())
            assertEquals(emptySet(), device.versesIn(FAVORITES))

            val favorites = device.collections.favorites().localId
            device.collections.addAyah(favorites, 2, 255, t(1))
            device.settle()
            device.collections.removeAyah(favorites, 2, 255)
            device.settle()

            assertEquals(emptySet(), Remote(server).versesIn(FAVORITES))
            assertEquals(1, Remote(server).bookmarkCount(), "the reading bookmark is kept")
            assertEquals("2:255", Remote(server).readingVerse())
            assertEquals(mapOf(1 to "2:255"), device.readingSlots())
            assertEquals(emptySet(), device.versesIn(FAVORITES))
        }
    }

    /** Name and location merge separately: a web rename survives this device's newer move, and vice versa. */
    @Test
    fun `a rename on the web survives a location change here`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.readingBookmarks.set(1, ReadingLocation.ayah(18, 10), t(0))
            device.settle()
            Remote(server).renameReadingBookmark(1, "Khatm", t(1))
            device.readingBookmarks.set(1, ReadingLocation.ayah(36, 1), t(2))

            device.settle()

            assertEquals(mapOf(1 to "36:1"), device.readingSlots())
            assertEquals("Khatm", device.readingBookmarks.readingBookmarks().single().name)
            assertEquals(mapOf(1 to "36:1"), Remote(server).readingSlots())
            assertEquals("Khatm", Remote(server).readingBookmarkName(1))
        }
    }

    /**
     * The location is one value: this device moves 18:10 to 18:20 (a new ayah), the web to 36:10 (a new sura). The
     * newer one wins whole; split fields would merge them into 36:20, where neither side ever was.
     */
    @Test
    fun `two moves of one slot never mix into a third location`() {
        listOf(true, false).forEach { localNewer ->
            FakeSyncServer().use { server ->
                Device(server).use { device ->
                    device.readingBookmarks.set(1, ReadingLocation.ayah(18, 10), t(0))
                    device.settle()
                    Remote(server).setReadingBookmark(1, 36, 10, if (localNewer) t(1) else t(3))
                    device.readingBookmarks.set(1, ReadingLocation.ayah(18, 20), t(2))

                    device.settle()

                    val expected = mapOf(1 to if (localNewer) "18:20" else "36:10")
                    assertEquals(expected, device.readingSlots(), "localNewer=$localNewer")
                    assertEquals(expected, Remote(server).readingSlots(), "localNewer=$localNewer")
                }
            }
        }
    }

    /**
     * Every server write replaces `metadata` (Joi defaults a missing one to null), keeping only `source`. This device
     * sends back what it has, so its writes don't wipe what the web stored.
     */
    @Test
    fun `slot metadata from the web survives this device's writes`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val metadata = buildJsonObject { put("source", "QDC_WEB"); put("color", "teal"); put("pinned", true) }
            Remote(server).setReadingBookmark(1, 18, 10, t(0), metadata)
            device.settle()
            val posts = server.postBodies.size

            device.readingBookmarks.rename(1, "Khatm", t(1))
            device.readingBookmarks.set(1, ReadingLocation.ayah(36, 1), t(2))
            device.settle()
            device.readingBookmarks.clear(1, t(3))
            device.settle()

            assertEquals(metadata, Remote(server).readingBookmarkMetadata(1))
            val sent = server.postBodies.drop(posts).map { it["mutations"]!!.jsonArray.single().jsonObject["data"] as JsonObject }
            sent.forEach { assertEquals(buildJsonObject { put("color", "teal"); put("pinned", true) }, it["metadata"]) }
            assertEquals(posts + 2, server.postBodies.size, "two writes, and no push of an echo")
        }
    }
}
