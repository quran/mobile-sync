package com.quran.shared.syncv2

import com.quran.shared.syncv2.fake.FakeSyncServer
import com.quran.shared.syncv2.fake.HttpError
import com.quran.shared.syncv2.fake.str
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** "The edit wins" against a delete, for parents with children: the generic rules, not collection-specific ones. */
class EditBeatsDeleteTest {
    private fun t(n: Int) = 1_760_000_000_000L + n * 1_000L

    /** Reviewer probe P1: the remote verse used to wedge sync on a foreign-key violation. */
    @Test
    fun `a verse added remotely to a collection deleted locally restores the collection with all its verses`() =
        FakeSyncServer().use { server ->
            Device(server).use { device ->
                val study = device.collections.addCollection("Study", t(0)).localId
                device.collections.addAyah(study, 1, 1, t(1))
                device.settle()
                val remote = Remote(server)
                remote.addToCollection(remote.collectionIdOf("Study"), 2, 255)
                device.collections.deleteCollection(study)

                device.settle()

                assertEquals(study, device.collectionId("Study"), "restored under its old local id")
                assertEquals(setOf("1:1", "2:255"), device.versesIn("Study"))
                assertEquals(setOf("Study"), remote.collectionNames())
                assertEquals(setOf("1:1", "2:255"), remote.versesIn("Study"))
            }
        }

    /** Reviewer probe P5: reverting a forbidden DELETE must bring back the verses the local delete cascaded. */
    @Test
    fun `a forbidden collection delete is reverted with its verses`() = FakeSyncServer().use { server ->
        server.includeMutationIndex = true
        Device(server).use { device ->
            val study = device.collections.addCollection("Study", t(0)).localId
            device.collections.addAyah(study, 1, 1, t(1))
            device.collections.addAyah(study, 2, 255, t(2))
            device.settle()
            server.rejectMutation = {
                if (it.str("type") == "DELETE" && it.str("resource") == "COLLECTION") HttpError(403, "ForbiddenError", "no") else null
            }
            device.collections.deleteCollection(study)

            val result = device.settle()

            assertEquals(emptyList(), result.held)
            assertEquals(setOf("1:1", "2:255"), device.versesIn("Study"))
            assertEquals(setOf("1:1", "2:255"), Remote(server).versesIn("Study"))
        }
    }

    /** Removed and added again before syncing, so its base matches; another device removed it meanwhile. */
    @Test
    fun `a verse re-added locally survives its remote removal`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val study = device.collections.addCollection("Study", t(0)).localId
            device.collections.addAyah(study, 2, 255, t(1))
            device.settle()
            val remote = Remote(server)
            remote.removeFromCollection(remote.collectionIdOf("Study"), 2, 255)
            device.collections.removeAyah(study, 2, 255)
            device.collections.addAyah(study, 2, 255, t(2))

            device.settle()

            assertEquals(setOf("2:255"), device.versesIn("Study"))
            assertEquals(setOf("2:255"), remote.versesIn("Study"))
            assertFalse(404 in server.postStatuses)
        }
    }
}
