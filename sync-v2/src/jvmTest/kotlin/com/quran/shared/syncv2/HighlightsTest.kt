package com.quran.shared.syncv2

import com.quran.shared.mutations.collectionNameKey
import com.quran.shared.syncv2.fake.FakeSyncServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Highlights are system collections (`system:highlights:<color>`): the server lets a client create them, but no
 * system collection may be renamed or deleted. The repository already refuses both; the raw writes below stand
 * in for a path that doesn't (older data, an importer), so the engine's own check is what is tested.
 */
class HighlightsTest {
    private fun t(n: Int) = 1_760_000_000_000L + n * 1_000L
    private val highlight = "system:highlights:blue"

    private fun Device.syncedHighlight(): Long {
        val id = collections.addCollection(highlight, t(0)).localId
        collections.addAyah(id, 2, 255, t(1))
        assertEquals(emptyList(), settle().held)
        return id
    }

    @Test
    fun `a highlight collection can be created and arrives read-only`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.syncedHighlight()

            assertEquals(setOf(highlight), Remote(server).collectionNames())
            assertEquals(setOf("2:255"), Remote(server).versesIn(highlight))
            assertTrue(device.collections.byName(highlight)!!.isSystem)
        }
    }

    @Test
    fun `any other system name is held as reserved`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.collections.addCollection("system:notes", t(0))
            device.collections.addCollection("system:highlights:red", t(1))

            assertEquals(listOf("RESERVED_NAME", "RESERVED_NAME"), device.settle().held.map { it.reason })
            assertEquals(emptySet(), Remote(server).collectionNames())
        }
    }

    @Test
    fun `renaming a highlight collection is held as read-only`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val id = device.syncedHighlight()
            device.database.collectionsQueries.collectionUpdate("Mine", collectionNameKey("Mine"), 1, t(2), id)

            assertEquals(listOf("READ_ONLY"), device.settle().held.map { it.reason })
            assertEquals(setOf(highlight), Remote(server).collectionNames())
        }
    }

    @Test
    fun `deleting a highlight collection is undone with its verses`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val id = device.syncedHighlight()
            device.database.collectionsQueries.collectionAyahsDeleteIn(id)
            device.database.collectionsQueries.collectionDelete(id)
            val posts = server.postBodies.size

            device.settle()

            assertEquals(setOf(highlight), device.collectionNames())
            assertEquals(setOf("2:255"), device.versesIn(highlight))
            assertEquals(posts, server.postBodies.size, "nothing is pushed")
            assertFalse(device.engine.held().any())
        }
    }

    /**
     * HL-07: 2:255 is blue on both devices; the phone makes it green, then the tablet (newer) makes it yellow.
     * One color per verse holds for remote changes too: the tablet removes the phone's green membership, and the
     * server ends with one color.
     */
    @Test
    fun `concurrent color changes converge on the newer color`() {
        listOf(true, false).forEach { tabletNewer ->
            FakeSyncServer().use { server ->
                Device(server).use { phone ->
                    Device(server).use { tablet ->
                        phone.highlight(2, 255, "blue", t(0))
                        phone.settle()
                        tablet.settle()

                        phone.highlight(2, 255, "green", if (tabletNewer) t(1) else t(2))
                        tablet.highlight(2, 255, "yellow", if (tabletNewer) t(2) else t(1))
                        phone.settle()
                        tablet.settle()
                        phone.settle()

                        val newer = if (tabletNewer) "yellow" else "green"
                        val remote = Remote(server)
                        val onServer = listOf("blue", "green", "yellow").filter { "2:255" in runCatching { remote.versesIn("$HIGHLIGHT$it") }.getOrDefault(emptySet()) }
                        assertEquals(listOf(newer), onServer, "tabletNewer=$tabletNewer")
                        assertEquals(mapOf("2:255" to setOf(newer)), phone.highlights(), "tabletNewer=$tabletNewer")
                        assertEquals(mapOf("2:255" to setOf(newer)), tablet.highlights(), "tabletNewer=$tabletNewer")
                    }
                }
            }
        }
    }

    /**
     * With three devices and the web. All three have 2:255 green (added at t1). A highlights it green again at
     * t5, which changes nothing on the server (its base stays t1). The web, which doesn't keep one color per verse,
     * adds yellow at t3. Every device must rank green by the server's t1, so all keep yellow: had A ranked by its own
     * t5, it would keep green while B and C keep yellow, and their pushes could remove both colors.
     */
    @Test
    fun `three devices rank a synced highlight by the server's time`() = FakeSyncServer().use { server ->
        Device(server).use { a ->
            Device(server).use { b ->
                Device(server).use { c ->
                    a.highlight(2, 255, "green", t(1))
                    listOf(a, b, c).forEach { it.settle() }
                    a.highlight(2, 255, "green", t(5))
                    val remote = Remote(server)
                    remote.addToCollection(remote.createCollection("${HIGHLIGHT}yellow"), 2, 255, at = t(3))

                    listOf(a, b, c).forEach { it.settle() }
                    listOf(a, b, c).forEach { it.settle() }

                    listOf(a, b, c).forEach { assertEquals(mapOf("2:255" to setOf("yellow")), it.highlights()) }
                    assertEquals(setOf("2:255"), remote.versesIn("${HIGHLIGHT}yellow"))
                    assertEquals(emptySet(), remote.versesIn("${HIGHLIGHT}green"))
                }
            }
        }
    }

    /** One color per verse applies to highlights only: a verse may be in any number of other collections. */
    @Test
    fun `a highlighted verse can also be in user collections`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.highlight(2, 255, "blue", t(0))
            val study = device.collections.addCollection("Study", t(1)).localId
            device.collections.addAyah(study, 2, 255, t(2))
            device.collections.addAyah(device.collections.favorites().localId, 2, 255, t(3))

            device.settle()

            assertEquals(mapOf("2:255" to setOf("blue")), device.highlights())
            assertEquals(setOf("2:255"), device.versesIn("Study"))
            assertEquals(setOf("2:255"), device.versesIn(Device.FAVORITES))
        }
    }

    private companion object {
        const val HIGHLIGHT = Device.HIGHLIGHTS
    }
}
