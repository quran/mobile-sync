package com.quran.shared.syncv2

import com.quran.shared.syncv2.Device.Companion.FAVORITES
import com.quran.shared.syncv2.HeldItem.Companion.WAITING_ON_PARENT
import com.quran.shared.syncv2.fake.FakeSyncServer
import com.quran.shared.syncv2.fake.HttpError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Failures that carry no item error: local validation, lost or dropped POSTs, a 409 race, edits during a POST, an
 * account switch, and the gateway's own codeless errors. The backend's `mutationIndex` can't change them, so unlike
 * [FailureHandlingTest] and [ErrorClassificationTest] they run once, against a server without it, as in production.
 */
class FailureWithoutItemErrorTest {
    private fun t(n: Int) = 1_760_000_000_000L + n * 1_000L

    private fun server() = FakeSyncServer()

    private fun Device.addWork() {
        sessions.addSession(1, 1, t(0))
        sessions.addSession(18, 1, t(1))
        sessions.addSession(2, 2, t(2))
        collections.addCollection("Study", t(3))
    }

    @Test
    fun `an invalid local item is held with its dependents and the rest syncs`() = server().use { server ->
        Device(server).use { device ->
            val long = device.collections.addCollection("x".repeat(300), t(0)).localId
            device.collections.addAyah(long, 2, 255, t(1))
            device.sessions.addSession(1, 1, t(2))

            val result = device.settle()

            val collection = ItemRef("COLLECTION", long)
            assertEquals(
                listOf(HeldItem("COLLECTION", long, "NAME_LENGTH"), HeldItem("COLLECTION_AYAH", 1, WAITING_ON_PARENT, collection)),
                result.held,
                "the verse waits on its collection"
            )
            assertEquals(setOf("1:1"), Remote(server).sessionVerses())
            assertEquals(emptySet(), Remote(server).collectionNames())

            device.collections.renameCollection(long, "Long", t(3))
            assertEquals(emptyList(), device.settle().held)
            assertEquals(setOf("2:255"), Remote(server).versesIn("Long"))
        }
    }

    /** Collections: the batch landed, the response was lost, and the user renamed before the retry. */
    @Test
    fun `a lost response whose batch landed binds instead of duplicating`() = server().use { server ->
        Device(server).use { device ->
            val study = device.collections.addCollection("Study", t(0)).localId
            device.collections.addAyah(device.collections.favorites().localId, 2, 255, t(1))
            device.sessions.addSession(1, 1, t(2))
            server.loseNextPostResponse = true

            assertIs<SyncResult.Failed>(device.sync())
            device.collections.renameCollection(study, "Memorize", t(3))
            device.settle()

            val remote = Remote(server)
            assertEquals(setOf("Memorize"), remote.collectionNames())
            assertEquals(setOf("Memorize"), device.collectionNames())
            assertEquals(setOf("2:255"), remote.versesIn(FAVORITES))
            assertEquals(setOf("1:1"), remote.sessionVerses())
            assertEquals(listOf("UPDATE COLLECTION"), server.mutationTypesOfLastPost())
        }
    }

    @Test
    fun `a POST that never landed is pushed again`() = server().use { server ->
        Device(server).use { device ->
            device.addWork()
            server.dropNextPost = true

            assertIs<SyncResult.Failed>(device.sync())
            device.settle()

            assertEquals(setOf("1:1", "18:1", "2:2"), Remote(server).sessionVerses())
            assertEquals(setOf("Study"), Remote(server).collectionNames())
        }
    }

    /** Another device pushes between this device's GET and POST: 409 OutOfSync, fetch, link, push again. */
    @Test
    fun `a concurrent create of the same name links instead of duplicating`() = server().use { server ->
        Device(server).use { device ->
            device.collections.addCollection("Study", t(0))
            server.duringNextPost = { Remote(server).createCollection("Study") }

            device.settle()

            assertEquals(409, server.postStatuses.first())
            assertEquals(setOf("Study"), Remote(server).collectionNames())
            assertEquals(setOf("Study"), device.collectionNames())
        }
    }

    /** An edit made while the POST is in flight is pushed by the next sync, never lost. */
    @Test
    fun `an edit made during the POST is pushed by the next sync`() = server().use { server ->
        Device(server).use { device ->
            val study = device.collections.addCollection("Study", t(0)).localId
            server.duringNextPost = { device.collections.renameCollection(study, "Memorize", t(1)) }

            device.settle()

            assertEquals(setOf("Memorize"), Remote(server).collectionNames())
            assertEquals(setOf("Memorize"), device.collectionNames())
        }
    }

    /** A different account wipes local data first, so nothing of A's is pushed into B. */
    @Test
    fun `an account switch wipes the previous account's data`() = server().use { server ->
        Device(server).use { device ->
            device.collections.addCollection("A synced", t(0))
            device.sessions.addSession(1, 1, t(1))
            device.settle()
            device.collections.addCollection("A pending", t(2))
            Remote(server, "user-b").createCollection("B's")

            device.account = "user-b"
            device.settle()

            assertEquals(setOf("B's"), device.collectionNames())
            assertEquals(emptyList(), device.sessionVerses())
            assertEquals(setOf("B's"), Remote(server, "user-b").collectionNames())
            assertEquals(setOf("A synced"), Remote(server, "user-a").collectionNames())
            assertEquals(emptyList(), device.engine.store.outboxes)
        }
    }

    /**
     * A 404 or 422 from the gateway itself (a deploy, a routing or maintenance page) carries no backend `error.code`:
     * it says nothing about any item, so nothing is bisected, held or forgotten, and no tombstone is dropped.
     */
    @Test
    fun `a codeless gateway 404 or 422 aborts without touching any item`() {
        listOf(404, 422).forEach { status ->
            server().use { server ->
                Device(server).use { device ->
                    val study = device.collections.addCollection("Study", t(0)).localId
                    val old = device.collections.addCollection("Old", t(1)).localId
                    device.settle()
                    device.collections.renameCollection(study, "Memorize", t(2))
                    device.collections.deleteCollection(old)
                    device.collections.addCollection("New", t(3))
                    val bases = device.engine.store.rows("COLLECTION")
                    server.rejectMutation = { HttpError(status, "", "Not Found") }
                    val posts = server.postStatuses.size

                    val failed = assertIs<SyncResult.Failed>(device.sync(), "$status")

                    assertTrue(failed.retryable)
                    assertEquals(listOf(status), server.postStatuses.drop(posts), "$status is not bisected")
                    assertEquals(emptyList(), device.engine.held())
                    assertEquals(bases, device.engine.store.rows("COLLECTION"), "bases and tombstones are kept")
                    server.rejectMutation = null
                    device.settle()
                    assertEquals(setOf("Memorize", "New"), Remote(server).collectionNames())
                }
            }
        }
    }
}
