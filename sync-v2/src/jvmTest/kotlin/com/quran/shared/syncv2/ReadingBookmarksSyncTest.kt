package com.quran.shared.syncv2

import com.quran.shared.syncv2.Device.Companion.FAVORITES
import com.quran.shared.syncv2.fake.FakeSyncServer
import com.quran.shared.syncv2.fake.int
import com.quran.shared.syncv2.fake.str
import com.quran.shared.syncv2.types.ReadingLocation
import com.quran.shared.syncv2.types.ServerMushaf
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reading bookmarks: three slots, keyed by slot, merged whole (the newer slot state wins). */
class ReadingBookmarksSyncTest {
    private fun t(n: Int) = 1_760_000_000_000L + n * 1_000L

    private fun FakeSyncServer.lastMutations() = postBodies.last()["mutations"]!!.jsonArray.map { it.jsonObject }

    /**
     * A slot sends the mushaf it carries, `mushafId` only. Today's backend keeps only the bookmark group and echoes
     * its stand-in (5 for `verses_6236` and `pages_604`, 7 and 6 for the IndoPak page groups), so a page set in mushaf 1
     * or 2 comes back as 5. The echo replaces the location and pushes nothing.
     */
    @Test
    fun `a slot sends its mushaf and takes the server's echo without pushing again`() {
        listOf(
            ReadingLocation.ayah(18, 10) to 5,
            ReadingLocation.page(300, ServerMushaf.QCF_V1) to 5,
            ReadingLocation.page(300, ServerMushaf.QCF_V2) to 5,
            ReadingLocation.page(300, ServerMushaf.INDOPAK_16_LINES) to 7,
            ReadingLocation.page(300, ServerMushaf.INDOPAK_15_LINES) to 6
        ).forEach { (location, echoed) ->
            FakeSyncServer().use { server ->
                Device(server).use { device ->
                    device.readingBookmarks.set(1, location, t(0))

                    device.settle()
                    device.settle()

                    val data = server.lastMutations().single()["data"] as JsonObject
                    assertEquals(location.mushafId, data.int("mushafId"), "$location")
                    assertTrue("mushaf" !in data && "group" !in data, "the resource accepts only mushafId")
                    assertEquals(1, server.postBodies.size, "$location: the echo pushed nothing")
                    val stored = device.readingBookmarks.readingBookmarks().single().location!!
                    assertEquals(Triple(location.type, location.key, echoed), Triple(stored.type, stored.key, stored.mushafId))
                }
            }
        }
    }

    /** The backend we'll ask for returns the written mushaf, so a page keeps it. */
    @Test
    fun `a page keeps its mushaf when the server returns it`() = FakeSyncServer().use { server ->
        Device(server).use { phone ->
            Device(server).use { tablet ->
                server.account(Device.ACCOUNT).keepsMushafId = true
                phone.readingBookmarks.set(1, ReadingLocation.page(584, ServerMushaf.QCF_V1), t(0))

                phone.settle()
                phone.settle()
                tablet.settle()

                assertEquals(1, server.postBodies.size, "only the set; its echo pushed nothing")
                listOf(phone, tablet).forEach {
                    assertEquals(ReadingLocation.page(584, ServerMushaf.QCF_V1), it.readingBookmarks.readingBookmarks().single().location)
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

    /** The upcoming backend renders `pages_604` with stand-in 1: the echo replaces the mushaf and pushes nothing. */
    @Test
    fun `a pages_604 echo with mushaf 1 pushes nothing`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            server.account(Device.ACCOUNT).pages604StandIn = 1
            device.readingBookmarks.set(1, ReadingLocation.page(300, ServerMushaf.QCF_V1), t(0))

            device.settle()
            device.settle()

            assertEquals(1, server.postBodies.size, "only the set; its echo pushed nothing")
            assertEquals(ReadingLocation.page(300, ServerMushaf.QCF_V2), device.readingBookmarks.readingBookmarks().single().location)
        }
    }

    /** A page of a mushaf set elsewhere is sent back as the server sent it: a rename isn't held. */
    @Test
    fun `a rename sends a page's mushaf back unchanged`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            server.account(Device.ACCOUNT).keepsMushafId = true
            Remote(server).setReadingPage(1, 300, mushafId = 3, updatedAt = t(0))
            device.settle()

            device.readingBookmarks.rename(1, "Khatm", t(1))

            assertEquals(emptyList(), device.settle().held.map { it.reason })
            val data = server.lastMutations().single()["data"] as JsonObject
            assertEquals(Triple("page", 300, 3), Triple(data.str("type"), data.int("key"), data.int("mushafId")))
            assertEquals(ReadingLocation.page(300, ServerMushaf.INDOPAK), device.readingBookmarks.readingBookmarks().single().location)
        }
    }

    /**
     * A page CREATE landed but its response was lost. The next GET binds it and its 2 -> 5 echo settles it: one push,
     * the slot ends on 5, and nothing is pushed again. This relies on the server keeping `clientUpdatedAt` to the ms.
     */
    @Test
    fun `a lost page create converges on the server's stand-in`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.readingBookmarks.set(1, ReadingLocation.page(300, ServerMushaf.QCF_V1), t(0))
            server.loseNextPostResponse = true

            assertIs<SyncResult.Failed>(device.sync())
            device.settle()
            device.settle()

            assertEquals(1, server.postBodies.size, "pushed once")
            assertEquals(mapOf(1 to "page 300"), Remote(server).readingSlots())
            assertEquals(ReadingLocation.page(300, ServerMushaf.KFGQPC_HAFS), device.readingBookmarks.readingBookmarks().single().location)
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
                    device.readingBookmarks.set(2, ReadingLocation.page(300, ServerMushaf.QCF_V1), t(1))
                    device.addNote("Written with the slots", "1:1", t(2))
                    server.dropFromNextResponse = { it.str("resource") == "READING_BOOKMARK" && (it["data"] as JsonObject).int("slot") == 1 }

                    device.settle()
                    assertEquals(1, server.postBodies.size, "index=$withIndex: nothing pushed twice")

                    device.readingBookmarks.set(2, ReadingLocation.page(301, ServerMushaf.QCF_V1), t(3))
                    device.settle()
                    device.settle()

                    assertEquals(mapOf(1 to "18:10", 2 to "page 301"), Remote(server).readingSlots(), "index=$withIndex")
                    assertEquals(listOf("Written with the slots"), Remote(server).noteBodies())
                    assertEquals(Remote(server).readingBookmarkId(2), server.lastMutations().single().str("resourceId"))
                    assertEquals(2, server.postBodies.size, "index=$withIndex: the 2 -> 5 echoes pushed nothing")
                    val page = device.readingBookmarks.readingBookmarks().single { it.slot == 2 }.location
                    assertEquals(ReadingLocation.page(301, ServerMushaf.KFGQPC_HAFS), page, "index=$withIndex")
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
