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

/** Collections and verses in collections against the new engine: S1, S2, S6, S9, S10, edit-vs-delete. */
class CollectionsSyncTest {
    private fun t(n: Int) = 1_760_000_000_000L + n * 1_000L

    @Test
    fun `collections and verses sync and their echoes are no-ops`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val study = device.collections.addCollection("Study", t(0))
            device.collections.addAyah(study.localId, 2, 255, t(1))
            device.collections.addAyah(device.collections.favorites().localId, 1, 1, t(2))

            device.syncOk()

            val remote = Remote(server)
            assertEquals(setOf("Study"), remote.collectionNames())
            assertEquals(setOf("2:255"), remote.versesIn("Study"))
            assertEquals(setOf("1:1"), remote.versesIn(FAVORITES))
            val posts = server.postBodies.size
            device.syncOk()
            assertEquals(posts, server.postBodies.size, "echoes and the collection bump must not be pushed back")
        }
    }

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

    /** S9, COL-16: a user collection named "Favorites" is a collection like any other, next to the default one. */
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

    @Test
    fun `verses another device adds arrive, including quran com bookmarks in Favorites`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val remote = Remote(server)
            val study = remote.createCollection("Study")
            remote.addToCollection(study, 2, 255)
            remote.bookmark(18, 10)

            device.syncOk()

            assertEquals(setOf("Study"), device.collectionNames())
            assertEquals(setOf("2:255"), device.versesIn("Study"))
            assertEquals(setOf("18:10"), device.versesIn(FAVORITES))
        }
    }

    /** S1 (collections): a never-synced create + delete leaves no tombstone, so another device's "Study" is safe. */
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

    /** S2: the create's echo (and the server's membership bump) can't revert a newer local rename. */
    @Test
    fun `a collection renamed after its create was pushed keeps the new name`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val study = device.collections.addCollection("Study", t(0))
            device.syncOk()
            device.collections.addAyah(study.localId, 2, 255, t(1))
            device.syncOk()

            device.collections.renameCollection(study.localId, "Memorize", t(2))
            device.settle()

            assertEquals(setOf("Memorize"), device.collectionNames())
            assertEquals(setOf("Memorize"), Remote(server).collectionNames())
            assertEquals(setOf("2:255"), Remote(server).versesIn("Memorize"))
        }
    }

    /** S6: removing a verse from Favorites deletes the bookmark on the server; the echo must not wedge sync. */
    @Test
    fun `removing a verse from Favorites syncs`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val favorites = device.collections.favorites().localId
            device.collections.addAyah(favorites, 2, 255, t(0))
            device.settle()

            device.collections.removeAyah(favorites, 2, 255)
            device.settle()

            assertEquals(emptySet(), device.versesIn(FAVORITES))
            assertEquals(emptySet(), Remote(server).versesIn(FAVORITES))
            assertEquals(0, Remote(server).bookmarkCount())
            assertFalse(server.postStatuses.any { it >= 400 })
        }
    }

    /** S6 (related): removing a synced verse from a collection takes one GET and one POST, no retry. */
    @Test
    fun `removing a synced verse from a collection syncs in one attempt`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val study = device.collections.addCollection("Study", t(0)).localId
            device.collections.addAyah(study, 2, 255, t(1))
            device.settle()

            device.collections.removeAyah(study, 2, 255)
            val gets = server.getCount
            val posts = server.postStatuses.size
            device.syncOk()

            assertEquals(1, server.getCount - gets)
            assertEquals(listOf(200), server.postStatuses.drop(posts))
            assertEquals(emptySet(), Remote(server).versesIn("Study"))
        }
    }

    /** Why Favorites maps to COLLECTION_BOOKMARK `__default__`, not BOOKMARK DELETE (which cascades). */
    @Test
    fun `removing a verse from Favorites keeps it in other collections`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val favorites = device.collections.favorites().localId
            val study = device.collections.addCollection("Study", t(0)).localId
            device.collections.addAyah(favorites, 2, 255, t(1))
            device.collections.addAyah(study, 2, 255, t(2))
            device.settle()

            device.collections.removeAyah(favorites, 2, 255)
            device.settle()

            assertEquals(emptySet(), Remote(server).versesIn(FAVORITES))
            assertEquals(setOf("2:255"), Remote(server).versesIn("Study"))
            assertEquals(setOf("2:255"), device.versesIn("Study"))
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

    /** S9 scenario 1: a remote rename onto a local unsynced name links the two instead of wedging. */
    @Test
    fun `a remote rename onto a local unsynced name links them`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val remote = Remote(server)
            val id = remote.createCollection("Study")
            remote.addToCollection(id, 2, 255)
            device.settle()
            remote.renameCollection(id, "Memorize")
            val local = device.collections.addCollection("Memorize", t(10)).localId
            device.collections.addAyah(local, 1, 1, t(11))

            device.settle()

            assertEquals(setOf("Memorize"), device.collectionNames())
            assertEquals(setOf("1:1", "2:255"), device.versesIn("Memorize"))
            assertEquals(setOf("Memorize"), remote.collectionNames())
            assertEquals(setOf("1:1", "2:255"), remote.versesIn("Memorize"))
        }
    }

    /** S9 scenario 2: B→C, A→B, C→D on the web; GET delivers A→B before B→D. Parked keys make it apply. */
    @Test
    fun `a chain of remote renames applies in one sync`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val remote = Remote(server)
            val a = remote.createCollection("A")
            val b = remote.createCollection("B")
            device.settle()

            remote.renameCollection(b, "C")
            remote.renameCollection(a, "B")
            remote.renameCollection(b, "D")
            device.syncOk()

            assertEquals(setOf("B", "D"), device.collectionNames())
        }
    }

    /** Reviewer probe P6: renames that take each other's names reach the server in an order it accepts. */
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

    /** S9 scenario 3: a local rename clashing with a remote create is reverted to base; nothing diverges. */
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

    /** S10/S8 for collections: a local rename against a remote delete recreates the collection (edit wins). */
    @Test
    fun `a collection renamed locally and deleted remotely is recreated`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val study = device.collections.addCollection("Study", t(0)).localId
            device.settle()
            val remote = Remote(server)
            remote.deleteCollection(remote.collectionIdOf("Study"))
            device.collections.renameCollection(study, "Memorize", t(10))

            device.settle()

            assertEquals(setOf("Memorize"), device.collectionNames())
            assertEquals(setOf("Memorize"), remote.collectionNames())
            assertFalse(404 in server.postStatuses)
        }
    }

    @Test
    fun `a verse added to a collection deleted remotely recreates the collection`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val study = device.collections.addCollection("Study", t(0)).localId
            device.collections.addAyah(study, 1, 1, t(1))
            device.settle()
            val remote = Remote(server)
            remote.deleteCollection(remote.collectionIdOf("Study"))
            device.collections.addAyah(study, 2, 255, t(10))

            device.settle()

            assertEquals(setOf("Study"), remote.collectionNames())
            assertEquals(setOf("2:255"), remote.versesIn("Study"), "1:1 was removed with the remote delete")
            assertEquals(setOf("2:255"), device.versesIn("Study"))
        }
    }

    /** Edit beats delete, the other way: a remote rename restores a locally deleted collection with its verses. */
    @Test
    fun `a collection deleted locally but renamed remotely comes back with its verses`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val study = device.collections.addCollection("Study", t(0)).localId
            device.collections.addAyah(study, 2, 255, t(1))
            device.settle()
            val remote = Remote(server)
            remote.renameCollection(remote.collectionIdOf("Study"), "Memorize")
            device.collections.deleteCollection(study)

            device.settle()

            assertEquals(setOf("Memorize"), device.collectionNames())
            assertEquals(setOf("2:255"), device.versesIn("Memorize"))
            assertEquals(setOf("2:255"), remote.versesIn("Memorize"))
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

    /** Both devices renamed the same collection: the newer clientUpdatedAt wins on both, with no ping-pong. */
    @Test
    fun `concurrent renames converge on the newer one`() = FakeSyncServer().use { server ->
        Device(server).use { a ->
            Device(server).use { b ->
                val studyOnA = a.collections.addCollection("Study", t(0)).localId
                a.settle()
                b.settle()
                a.collections.renameCollection(studyOnA, "Memorize", t(10))
                b.collections.renameCollection(b.collectionId("Study"), "Review", t(20))

                a.settle()
                b.settle()
                a.settle()

                assertEquals(setOf("Review"), a.collectionNames())
                assertEquals(setOf("Review"), b.collectionNames())
                assertEquals(setOf("Review"), Remote(server).collectionNames())
            }
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

    /** Older-app color collections are read-only from GET data, so no 403 is ever produced. */
    @Test
    fun `protected color collections arrive read-only`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val account = server.account(Device.ACCOUNT)
            val id = Remote(server).createCollection("Blue")
            account.collections[id] = account.collections.getValue(id).copy(source = "QDC_IOS")

            device.syncOk()

            assertTrue(device.collections.byName("Blue")!!.isSystem)
        }
    }
}
