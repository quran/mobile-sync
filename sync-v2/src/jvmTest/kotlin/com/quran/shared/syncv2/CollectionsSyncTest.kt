package com.quran.shared.syncv2

import com.quran.shared.syncv2.Device.Companion.FAVORITES
import com.quran.shared.syncv2.fake.FakeSyncServer
import com.quran.shared.syncv2.fake.HttpError
import com.quran.shared.syncv2.fake.str
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Collections and verses in collections. */
class CollectionsSyncTest {
    private fun t(n: Int) = 1_760_000_000_000L + n * 1_000L

    /** Favorites is linked to the server's virtual `__default__` collection like any item, and never pushed. */
    @Test
    fun `Favorites links to the server's default collection and is never pushed`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val favorites = device.collections.favorites().localId
            device.collections.addAyah(favorites, 1, 1, t(0))

            device.settle()
            device.account = "user-b"
            device.settle()
            device.collections.addAyah(device.collections.favorites().localId, 2, 2, t(1))
            device.settle()

            assertEquals("__default__", device.engine.store.row("COLLECTION", device.collections.favorites().localId)?.remoteId)
            assertFalse(server.postBodies.any { "\"resource\":\"COLLECTION\"" in it.toString() })
            assertEquals(setOf("1:1"), Remote(server).versesIn(FAVORITES))
            assertEquals(setOf("2:2"), Remote(server, "user-b").versesIn(FAVORITES))
        }
    }

    /** COL-16: a user collection named "Favorites" is a collection like any other, next to the default one. */
    @Test
    fun `a web collection named Favorites arrives next to the default one`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val remote = Remote(server)
            remote.addToCollection(remote.createCollection("Favorites"), 4, 1)
            device.collections.addAyah(device.collections.favorites().localId, 1, 1, t(0))

            device.settle()

            assertEquals(setOf("Favorites"), device.collectionNames())
            assertEquals(setOf("4:1"), device.versesIn(device.collectionId("Favorites")))
            assertEquals(setOf("1:1"), device.versesIn(FAVORITES))
            assertEquals(setOf("1:1"), remote.versesIn(FAVORITES))
        }
    }

    /** Without `__default__` in the GET, Favorites can't be linked: its verses must not wait silently. */
    @Test
    fun `Favorites that never links is reported with the verses waiting on it`() = FakeSyncServer().use { server ->
        server.omitDefaultCollection = true
        Device(server).use { device ->
            val favorites = device.collections.favorites().localId
            device.collections.addAyah(favorites, 1, 1, t(0))

            val held = device.settle().held

            val ref = ItemRef("COLLECTION", favorites)
            assertEquals(
                listOf(HeldItem("COLLECTION", favorites, HeldItem.NEVER_LINKED), HeldItem("COLLECTION_AYAH", 1, HeldItem.WAITING_ON_PARENT, ref)),
                held
            )
        }
    }

    /** A never-synced create + delete leaves no tombstone, so another device's "Study" is safe. */
    @Test
    fun `creating and deleting an unsynced collection twice does not delete another device's collection`() =
        FakeSyncServer().use { server ->
            Device(server).use { device ->
                repeat(2) { device.collections.deleteCollection(device.collections.addCollection("Study", t(it)).localId) }
                Remote(server).createCollection("Study")

                device.settle()

                assertEquals(setOf("Study"), Remote(server).collectionNames())
                assertEquals(setOf("Study"), device.collectionNames())
                assertFalse(server.postBodies.any { it.toString().contains("DELETE") })
            }
        }

    /** Why Favorites maps to COLLECTION_BOOKMARK `__default__`: BOOKMARK CREATE on an existing bookmark ignores Favorites. */
    @Test
    fun `adding a verse to Favorites that is already in another collection puts it in Favorites`() =
        FakeSyncServer().use { server ->
            Device(server).use { device ->
                val study = device.collections.addCollection("Study", t(0)).localId
                device.collections.addAyah(study, 2, 255, t(1))
                device.settle()

                device.collections.addAyah(device.collections.favorites().localId, 2, 255, t(2))
                device.settle()

                assertEquals(setOf("2:255"), Remote(server).versesIn(FAVORITES))
                assertEquals(setOf("2:255"), Remote(server).versesIn("Study"))
            }
        }

    /** Renames that take each other's names reach the server in an order it accepts. */
    @Test
    fun `a shift and a swap of collection names push without a duplicate-name error`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val ids = listOf("A", "B", "X", "Y").associateWith { device.collections.addCollection(it, t(0)).localId }
            device.collections.addAyah(ids.getValue("A"), 2, 255, t(1))
            device.settle()
            val remote = Remote(server)
            val remoteIds = listOf("A", "B", "X", "Y").associateWith(remote::collectionIdOf)

            // A shift, A -> B -> C (B moves first locally), and a swap, X <-> Y (through a temporary name locally).
            device.collections.renameCollection(ids.getValue("B"), "C", t(2))
            device.collections.renameCollection(ids.getValue("A"), "B", t(3))
            device.collections.renameCollection(ids.getValue("X"), "tmp", t(4))
            device.collections.renameCollection(ids.getValue("Y"), "X", t(5))
            device.collections.renameCollection(ids.getValue("X"), "Y", t(6))
            val result = device.settle()

            assertEquals(emptyList(), result.held)
            assertFalse(409 in server.postStatuses, "${server.postStatuses}")
            val renamed = mapOf("A" to "B", "B" to "C", "X" to "Y", "Y" to "X")
            renamed.forEach { (old, new) -> assertEquals(remoteIds.getValue(old), remote.collectionIdOf(new), "$old -> $new") }
            assertEquals(renamed.values.toSet(), device.collectionNames())
            assertEquals(setOf("2:255"), remote.versesIn("B"), "the verse moved with its collection")
        }
    }

    /**
     * A swap's temporary name is refused (422). The hold must record the real name, or the next plan sees a change,
     * releases it and sends the temporary rename again on every phase, starving every other change.
     */
    @Test
    fun `a swap whose temporary name is refused is held once, and the rest lands`() = FakeSyncServer().use { server ->
        server.includeMutationIndex = true
        server.rejectMutation = {
            val name = (it["data"] as? JsonObject)?.str("name")
            if (name?.endsWith("(2)") == true) HttpError(422, "ValidationError", "name refused") else null
        }
        Device(server).use { device ->
            val x = device.collections.addCollection("X", t(0)).localId
            val y = device.collections.addCollection("Y", t(1)).localId
            device.settle()
            device.collections.renameCollection(x, "tmp", t(2))
            device.collections.renameCollection(y, "X", t(3))
            device.collections.renameCollection(x, "Y", t(4))
            device.sessions.addSession(2, 255, t(5))

            val held = device.settle().held

            assertEquals(setOf("2:255"), Remote(server).sessionVerses(), "the unrelated session landed")
            assertTrue(held.any { it.localId == x && it.reason == "422 ValidationError" }, "$held")
            assertTrue(
                HeldItem("COLLECTION", y, HeldItem.WAITING_ON_KEY, ItemRef("COLLECTION", x)) in held,
                "the other half of the swap waits on the held one instead of failing on its name: $held"
            )
            assertTrue(server.postStatuses.size < 10, "no loop: ${server.postStatuses}")
            val posts = server.postBodies.size
            device.syncOk()
            assertEquals(posts, server.postBodies.size, "the next run doesn't send it again")
        }
    }

    /** A local rename clashing with a remote create is reverted to base; nothing diverges. */
    @Test
    fun `a local rename clashing with a remote create is reverted`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val study = device.collections.addCollection("Study", t(0)).localId
            device.settle()
            device.collections.renameCollection(study, "Memorize", t(10))
            Remote(server).createCollection("Memorize")

            device.settle()

            assertEquals(setOf("Study", "Memorize"), device.collectionNames())
            assertEquals(setOf("Study", "Memorize"), Remote(server).collectionNames())
        }
    }

    @Test
    fun `a collection deleted locally deletes it and its verses on the server`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val study = device.collections.addCollection("Study", t(0)).localId
            device.collections.addAyah(study, 2, 255, t(1))
            device.settle()

            device.collections.deleteCollection(study)
            device.settle()

            assertEquals(emptySet(), Remote(server).collectionNames())
            assertEquals(listOf("DELETE COLLECTION"), server.mutationTypesOfLastPost(), "the parent covers the child")
            assertTrue(device.engine.store.rows("COLLECTION_AYAH").isEmpty())
        }
    }

    /**
     * The server strips tags, escapes `&`, `<`, `>` and trims (sanitize-html with no allowed tags). Escaped text
     * round trips unchanged; stripped tags come back as a one-time fast-forward, never as a second push.
     */
    @Test
    fun `escaped and trimmed names round trip exactly without a second push`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.collections.addCollection("  Tafsir <b>notes</b> & more\u00A0", t(0))
            device.collections.addCollection("1 < 2 > 0", t(1))
            device.syncOk()
            val posts = server.postBodies.size

            device.syncOk()
            device.syncOk()

            assertEquals(posts, server.postBodies.size)
            // Sent escaped, so text that looks like a tag is kept, not stripped by sanitize-html (regression).
            assertEquals(setOf("Tafsir <b>notes</b> & more", "1 < 2 > 0"), device.collectionNames())
            assertEquals(setOf("Tafsir &lt;b&gt;notes&lt;/b&gt; &amp; more", "1 &lt; 2 &gt; 0"), Remote(server).collectionNames())
        }
    }
}
