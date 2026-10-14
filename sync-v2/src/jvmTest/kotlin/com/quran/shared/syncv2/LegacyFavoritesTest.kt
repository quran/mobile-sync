package com.quran.shared.syncv2

import com.quran.shared.syncv2.Device.Companion.FAVORITES
import com.quran.shared.syncv2.fake.FakeAccount.Companion.DEFAULT_ID
import com.quran.shared.syncv2.fake.FakeSyncServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Favorites written before quran.com-users-backend#1491 have no `__default__` membership log: GET reports them only as
 * BOOKMARK entries with `isInDefaultCollection`. They are read from that flag ([FakeSyncServer.legacyFavorites]).
 */
class LegacyFavoritesTest {
    private fun t(n: Int) = 1_760_000_000_000L + n * 1_000L

    @Test
    fun `legacy Favorites arrive on the first sync and nothing is pushed back`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val remote = Remote(server)
            val study = remote.createCollection("Study")
            server.legacyFavorites = true
            remote.bookmark(1, 2)
            remote.bookmark(3, 1)
            // In a collection only, and a reading bookmark: neither is in Favorites.
            remote.addToCollection(study, 2, 255)
            remote.setReadingBookmark(1, 5, 3, t(0))
            server.legacyFavorites = false
            remote.bookmark(4, 1)
            // The same verse added here before the first sync links to the legacy one.
            device.collections.addAyah(device.collections.favorites().localId, 1, 2, t(1))

            device.settle()

            assertEquals(setOf("1:2", "3:1", "4:1"), device.versesIn(FAVORITES))
            assertEquals(setOf("2:255"), device.versesIn("Study"))
            assertEquals(emptyList(), server.postBodies, "nothing to push")
        }
    }

    @Test
    fun `a legacy favorite follows its flag, its bookmark delete and a later membership delete`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val remote = Remote(server)
            val study = remote.createCollection("Study")
            server.legacyFavorites = true
            val (flipped, deleted) = listOf(remote.bookmark(1, 2), remote.bookmark(1, 3))
            remote.bookmark(1, 4)
            listOf(2, 4).forEach { remote.addToCollection(study, 1, it) }
            device.settle()
            assertEquals(setOf("1:2", "1:3", "1:4"), device.versesIn(FAVORITES))

            // Still before #1491: the flag flips and the bookmark goes with no membership log.
            remote.removeFromCollection(DEFAULT_ID, 1, 2)
            remote.deleteBookmark(deleted)
            device.settle()
            assertEquals(setOf("1:4"), device.versesIn(FAVORITES), "the flag of $flipped is false and $deleted is gone")
            assertEquals(setOf("1:2", "1:4"), device.versesIn("Study"))

            remote.addToCollection(DEFAULT_ID, 1, 2)
            device.settle()
            assertEquals(setOf("1:2", "1:4"), device.versesIn(FAVORITES), "the flag of $flipped is true again")

            // Today's backend logs the membership DELETE.
            server.legacyFavorites = false
            remote.removeFromCollection(DEFAULT_ID, 1, 4)
            device.settle()
            assertEquals(setOf("1:2"), device.versesIn(FAVORITES))
            assertEquals(emptyList(), server.postBodies, "nothing to push")
        }
    }

    /** Each change to the bookmark sends it again; the verse keeps the date it was added and nothing is pushed. */
    @Test
    fun `a later bookmark update keeps a legacy favorite's added date`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val remote = Remote(server)
            server.legacyFavorites = true
            remote.bookmark(1, 2)
            device.settle()
            val added = device.addedAt(1, 2)

            remote.setReadingBookmark(1, 1, 2, t(5))
            remote.bookmark(1, 2)
            device.settle()

            assertEquals(added, device.addedAt(1, 2), "slot 1 moving onto it and a re-bookmark leave the date alone")
            assertEquals(setOf("1:2"), device.versesIn(FAVORITES))
            assertEquals(emptyList(), server.postBodies, "nothing to push")
        }
    }

    private fun Device.addedAt(sura: Int, ayah: Int): Long = database.collectionsQueries.collectionAyahsAll().executeAsList()
        .single { it.collection_local_id == collections.favorites().localId && it.sura == sura.toLong() && it.ayah == ayah.toLong() }
        .created_at

    @Test
    fun `removing a legacy favorite here pushes one key-based delete`()= FakeSyncServer().use { server ->
        Device(server).use { device ->
            val remote = Remote(server)
            server.legacyFavorites = true
            remote.bookmark(1, 2)
            remote.bookmark(1, 3)
            server.legacyFavorites = false
            device.settle()

            device.collections.removeAyah(device.collections.favorites().localId, 1, 2)
            device.settle()

            assertEquals(setOf("1:3"), remote.versesIn(FAVORITES))
            assertEquals(setOf("1:3"), device.versesIn(FAVORITES))
            assertEquals(listOf("DELETE COLLECTION_BOOKMARK"), server.mutationTypesOfLastPost())
            assertTrue(server.postBodies.size == 1, "one POST: ${server.postBodies}")
        }
    }
}
