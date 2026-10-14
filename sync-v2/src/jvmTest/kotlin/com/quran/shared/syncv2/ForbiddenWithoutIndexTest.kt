package com.quran.shared.syncv2

import com.quran.shared.syncv2.fake.FakeSyncServer
import com.quran.shared.syncv2.fake.HttpError
import com.quran.shared.syncv2.fake.str
import kotlin.test.Test
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A 403 from a server without `mutationIndex` is bisected: a scope error forbids every part, an item 403 only one. */
class ForbiddenWithoutIndexTest {
    private fun t(n: Int) = 1_760_000_000_000L + n * 1_000L

    @Test
    fun `an item 403 is found by bisecting and reverted with its children, and the rest lands`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val study = device.collections.addCollection("Study", t(0)).localId
            device.collections.addAyah(study, 2, 255, t(1))
            device.settle()
            server.rejectMutation = {
                if (it.str("type") == "DELETE" && it.str("resource") == "COLLECTION") HttpError(403, "ForbiddenError", "Forbidden") else null
            }
            device.collections.deleteCollection(study)
            device.sessions.addSession(1, 1, t(2))

            assertEquals(emptyList(), device.settle().held)

            assertEquals(setOf("1:1"), Remote(server).sessionVerses(), "the rest landed")
            assertEquals(setOf("2:255"), device.versesIn("Study"), "the forbidden delete was undone with its verses")
        }
    }

    /** Two forbidden items must not make every half fail and read as a scope error for good. */
    @Test
    fun `two forbidden deletes are both reverted once something else lands`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val a = device.collections.addCollection("A", t(0)).localId
            val b = device.collections.addCollection("B", t(1)).localId
            device.collections.addAyah(b, 1, 1, t(2))
            device.settle()
            server.rejectMutation = {
                if (it.str("type") == "DELETE" && it.str("resource") == "COLLECTION") HttpError(403, "ForbiddenError", "Forbidden") else null
            }
            device.collections.deleteCollection(a)
            device.collections.deleteCollection(b)
            device.sessions.addSession(1, 1, t(3))

            assertEquals(emptyList(), device.settle().held)

            assertEquals(setOf("1:1"), Remote(server).sessionVerses())
            assertEquals(setOf("A", "B"), device.collectionNames(), "both forbidden deletes were undone")
            assertEquals(setOf("1:1"), device.versesIn("B"))
        }
    }

    /**
     * Without an index, a bisect posts the earlier half first and handles only its error: here B's rename is refused,
     * and the later CREATE of "B" (which only fails because B still has the name) waits on it instead of being blamed.
     */
    @Test
    fun `a bisect blames only the earlier half and lets the later one wait`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val b = device.collections.addCollection("B", t(0)).localId
            device.settle()
            server.rejectMutation = {
                if ((it["data"] as? JsonObject)?.str("name") == "Refused") HttpError(422, "ValidationError", "refused") else null
            }
            device.collections.renameCollection(b, "Refused", t(1))
            val newB = device.collections.addCollection("B", t(2)).localId
            device.sessions.addSession(1, 1, t(3))

            val held = device.settle().held

            assertEquals(
                listOf(HeldItem("COLLECTION", b, "422 ValidationError"), HeldItem("COLLECTION", newB, HeldItem.WAITING_ON_KEY, ItemRef("COLLECTION", b))),
                held
            )
            assertFalse(409 in server.postStatuses, "${server.postStatuses}")
            assertEquals(setOf("1:1"), Remote(server).sessionVerses())
        }
    }

    @Test
    fun `a 403 on every part is a scope error, which stops with a long backoff`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val study = device.collections.addCollection("Study", t(0)).localId
            device.settle()
            server.rejectMutation = { HttpError(403, "ForbiddenError", "Forbidden") }
            device.collections.renameCollection(study, "Memorize", t(1))
            device.sessions.addSession(1, 1, t(2))

            val failed = assertIs<SyncResult.Failed>(device.sync())

            assertTrue(failed.retryable)
            assertEquals(Pusher.FORBIDDEN_BACKOFF_MILLIS, failed.retryAfterMillis)
            assertEquals(setOf("Memorize"), device.collectionNames(), "nothing was reverted")
            assertEquals(listOf(403, 403, 403), server.postStatuses.drop(1), "the batch, then each half")
        }
    }
}
