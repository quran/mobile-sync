package com.quran.shared.syncv2

import com.quran.shared.syncv2.Device.Companion.FAVORITES
import com.quran.shared.syncv2.fake.FakeSyncServer
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** End-to-end scenarios: more devices, bigger pushes, lost and partial requests. */
class ScenariosTest {
    private fun t(n: Int) = 1_760_000_000_000L + n * 1_000L

    private fun Device.snapshot() = Triple(
        collectionNames().associateWith(::versesIn),
        versesIn(FAVORITES),
        sessionVerses().toSet()
    )

    @Test
    fun `three devices editing the same account converge`() = FakeSyncServer().use { server ->
        Device(server).use { a ->
            Device(server).use { b ->
                Device(server).use { c ->
                    val study = a.collections.addCollection("Study", t(0)).localId
                    a.collections.addAyah(study, 1, 1, t(1))
                    a.sessions.addSession(1, 1, t(2))
                    listOf(a, b, c).forEach { it.settle() }

                    b.collections.renameCollection(b.collectionId("Study"), "Memorize", t(10))
                    c.collections.addAyah(c.collectionId("Study"), 2, 255, t(11))
                    c.collections.addCollection("Tafsir", t(12))
                    a.collections.addCollection("Tafsir", t(13))
                    a.collections.addAyah(a.collections.favorites().localId, 18, 10, t(14))
                    b.sessions.addSession(2, 2, t(15))
                    c.sessions.deleteSession(1, 1)

                    repeat(2) { listOf(b, c, a).forEach { it.syncOk() } }
                    listOf(a, b, c).forEach { it.settle() }

                    val expected = Triple(
                        mapOf("Memorize" to setOf("1:1", "2:255"), "Tafsir" to emptySet()),
                        setOf("18:10"),
                        setOf("2:2")
                    )
                    assertEquals(expected, a.snapshot())
                    assertEquals(expected, b.snapshot())
                    assertEquals(expected, c.snapshot())
                    assertEquals(setOf("Memorize", "Tafsir"), Remote(server).collectionNames())
                }
            }
        }
    }

    /** The collection's CREATE lands in phase 1; the response to phase 2 (its verse) is lost. */
    @Test
    fun `a lost response in the second phase binds the child on the next sync`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val study = device.collections.addCollection("Study", t(0)).localId
            device.collections.addAyah(study, 2, 255, t(1))
            server.loseResponseOfPost = 2

            assertIs<SyncResult.Failed>(device.sync())
            assertEquals(2, server.postBodies.size, "phase 1 (collection) and phase 2 (verse)")
            device.settle()

            assertEquals(2, server.postBodies.size, "the outbox bound the verse: nothing was pushed again")
            assertEquals(setOf("2:255"), Remote(server).versesIn("Study"))
            assertTrue(device.engine.store.rows("COLLECTION_AYAH").values.single().remoteId != null)
        }
    }

    /** Another device writes between GET pages: the pages don't describe one state, so the fetch starts over. */
    @Test
    fun `a head that changes during pagination is fetched again in the same run`() = FakeSyncServer().use { server ->
        Device(server, pageSize = 2).use { device ->
            val remote = Remote(server)
            repeat(4) { remote.createSession(1, it + 1, t(it)) }
            server.beforeGetPage = 2 to { remote.createSession(9, 9, t(10)) }

            device.syncOk()

            assertEquals(setOf("1:1", "1:2", "1:3", "1:4", "9:9"), device.sessionVerses().toSet())
            assertEquals(2 + 3, server.getCount, "two pages until the head moved, then all three again")
        }
    }

    /** When the head keeps moving, the run fails as retryable without merging any page, and the next one recovers. */
    @Test
    fun `a head that keeps changing during pagination fails the run without merging`() = FakeSyncServer().use { server ->
        Device(server, pageSize = 2).use { device ->
            val remote = Remote(server)
            repeat(4) { remote.createSession(1, it + 1, t(it)) }
            var writes = 0
            fun writeBetweenPages() {
                server.beforeGetPage = 2 to {
                    remote.createSession(9, ++writes, t(10 + writes))
                    if (writes < 3) writeBetweenPages()
                }
            }
            writeBetweenPages()

            val failed = assertIs<SyncResult.Failed>(device.sync())

            assertTrue(failed.retryable, failed.reason)
            assertEquals(emptyList(), device.sessionVerses(), "no page was merged")
            assertEquals(0L, device.engine.store.token)
            device.settle()
            assertEquals(7, device.sessionVerses().size)
        }
    }

    @Test
    fun `more than 100 changes are pushed in chained batches of 100`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val favorites = device.collections.favorites().localId
            (1..150).forEach { device.collections.addAyah(favorites, 2, it, t(it)) }

            device.settle()

            assertEquals(listOf(100, 50), server.postBodies.map { it["mutations"]!!.jsonArray.size })
            assertEquals(listOf(200, 200), server.postStatuses)
            assertEquals(150, Remote(server).versesIn(FAVORITES).size)
        }
    }

    /**
     * The app dies after persisting the outbox and before the POST lands: the dropped connection leaves the same state
     * (outbox written, nothing applied), and a new engine on the same database stands in for the restarted app.
     */
    @Test
    fun `a crash between persisting the outbox and the POST pushes exactly once`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.collections.addCollection("Study", t(0))
            device.sessions.addSession(1, 1, t(1))
            server.dropNextPost = true

            assertIs<SyncResult.Failed>(device.sync())
            assertEquals(1, device.engine.store.outboxes.size, "the outbox was persisted before the POST")
            device.upgrade("1")
            device.settle()

            assertEquals(setOf("Study"), Remote(server).collectionNames())
            assertEquals(setOf("1:1"), Remote(server).sessionVerses())
            assertEquals(listOf(-1, 200), server.postStatuses)
        }
    }

    /** A verse added on A to a collection B deleted; everything settles without more pushes. */
    @Test
    fun `a verse added to a collection another device deleted settles on both`() = FakeSyncServer().use { server ->
        Device(server).use { a ->
            Device(server).use { b ->
                val study = a.collections.addCollection("Study", t(0)).localId
                a.collections.addAyah(study, 1, 1, t(1))
                a.collections.addAyah(a.collections.favorites().localId, 1, 1, t(1))
                a.settle()
                b.settle()
                b.collections.deleteCollection(b.collectionId("Study"))
                a.collections.addAyah(study, 2, 2, t(5))

                b.settle()
                a.settle()
                b.settle()

                assertEquals(setOf("2:2"), a.versesIn("Study"), "the edit wins: recreated with the new verse")
                assertEquals(a.snapshot(), b.snapshot())
                assertEquals(setOf("1:1"), Remote(server).versesIn(FAVORITES))
            }
        }
    }

    /** quran.com removes a bookmark with BOOKMARK DELETE; it leaves Favorites here too. */
    @Test
    fun `a bookmark deleted on quran com leaves Favorites`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val remote = Remote(server)
            val id = remote.bookmark(2, 255)
            device.settle()
            assertEquals(setOf("2:255"), device.versesIn(FAVORITES))

            remote.deleteBookmark(id)
            device.settle()

            assertEquals(emptySet(), device.versesIn(FAVORITES))
        }
    }

    /** Another device writes during the POST; the gateway-wrapped 409 restarts the round. */
    @Test
    fun `another device writing during the POST restarts and converges`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.sessions.addSession(1, 1, t(0))
            val study = device.collections.addCollection("Study", t(2)).localId
            device.settle()
            device.collections.renameCollection(study, "Memorize", t(3))
            device.sessions.addSession(3, 3, t(4))
            server.duringNextPost = { Remote(server).createSession(9, 9, t(5)) }

            device.settle()

            assertEquals(409, server.postStatuses[1])
            assertEquals(setOf("Memorize"), Remote(server).collectionNames())
            assertEquals(setOf("1:1", "3:3", "9:9"), Remote(server).sessionVerses())
            assertEquals(Remote(server).sessionVerses(), device.sessionVerses().toSet())
        }
    }
}
