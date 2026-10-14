package com.quran.shared.syncv2

import com.quran.shared.syncv2.fake.FakeSyncServer
import com.quran.shared.syncv2.fake.HttpError
import com.quran.shared.syncv2.fake.str
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Holds, error classification and bisecting. [FailureWithoutItemErrorTest] has the failures the index can't change.
 * Runs against a server with and without `mutationIndex`: see the subclasses at the end.
 */
abstract class FailureHandlingTest(private val withIndex: Boolean) {
    private fun t(n: Int) = 1_760_000_000_000L + n * 1_000L

    private fun server() = FakeSyncServer().apply { includeMutationIndex = withIndex }

    private fun Device.addWork() {
        sessions.addSession(1, 1, t(0))
        sessions.addSession(18, 1, t(1))
        sessions.addSession(2, 2, t(2))
        collections.addCollection("Study", t(3))
    }

    @Test
    fun `a mutation the server rejects is held and the rest syncs`() = server().use { server ->
        server.rejectMutation = { if (it.isSessionAt(18, 1)) HttpError(422, "ValidationError", "rejected") else null }
        Device(server).use { device ->
            device.addWork()

            val result = device.settle()

            assertEquals(listOf("422 ValidationError"), result.held.map { it.reason })
            assertEquals(setOf("1:1", "2:2"), Remote(server).sessionVerses())
            assertEquals(setOf("Study"), Remote(server).collectionNames())
            if (withIndex) {
                assertEquals(listOf(422, 200), server.postStatuses, "held by its index")
            } else {
                assertTrue(server.postStatuses.size > 2, "isolated by bisecting: ${server.postStatuses}")
            }
        }
    }

    /** A refused DELETE is held as a tombstone; the next merge must keep that hold, not send it again. */
    @Test
    fun `a held delete stays held`() = server().use { server ->
        Device(server).use { device ->
            val study = device.collections.addCollection("Study", t(0)).localId
            device.settle()
            server.rejectMutation = {
                if (it.str("type") == "DELETE") HttpError(422, "ValidationError", "refused") else null
            }
            device.collections.deleteCollection(study)

            assertEquals(listOf("422 ValidationError"), device.settle().held.map { it.reason })
            assertEquals(setOf("Study"), Remote(server).collectionNames())
        }
    }

    /** Server-side validation runs before the head check, so a stale head doesn't hide a bad item. */
    @Test
    fun `an invalid item is held even when the head is stale`() = server().use { server ->
        Device(server).use { device ->
            device.sessions.addSession(1, 1, t(0))
            device.collections.addCollection("Study", t(1))
            // A Joi rule the client can't predict, checked before the head.
            server.rejectInValidation = { if (it.str("resource") == "COLLECTION") HttpError(422, "ValidationError", "invalid") else null }
            server.duringNextPost = { Remote(server).createSession(9, 9, t(2)) }

            val result = device.settle()

            assertEquals(listOf("422 ValidationError"), result.held.map { it.reason })
            assertEquals(setOf("1:1", "9:9"), Remote(server).sessionVerses())
            assertEquals(422, server.postStatuses.first(), "422 comes before the 409 head check")
        }
    }

    @Test
    fun `a duplicate name the server reports after a refetch is held`() = server().use { server ->
        server.rejectMutation = {
            if (it.str("resource") == "COLLECTION") HttpError(409, "UniqueViolationError", "duplicate") else null
        }
        Device(server).use { device ->
            device.addWork()

            val result = device.settle()

            assertEquals(listOf("409 UniqueViolationError"), result.held.map { it.reason })
            assertEquals(setOf("1:1", "18:1", "2:2"), Remote(server).sessionVerses())
        }
    }

    /** The held name may be freed by another device: a remote change to collections retries it; a day is the backstop. */
    @Test
    fun `a duplicate-name hold is retried after a remote change, or after a day`() {
        listOf("remote change", "a day").forEach { release ->
            server().use { server ->
                server.rejectMutation = {
                    if (it.str("resource") == "COLLECTION") HttpError(409, "UniqueViolationError", "duplicate") else null
                }
                Device(server).use { device ->
                    device.collections.addCollection("Study", t(0))
                    assertEquals(listOf("409 UniqueViolationError"), device.settle().held.map { it.reason })
                    val row = device.engine.store.rows("COLLECTION").values.single { it.heldReason != null }
                    assertEquals(Pusher.UNIQUE_HOLD_MILLIS, row.heldUntil!! - device.now)
                    server.rejectMutation = null
                    device.settle()
                    assertEquals(emptySet(), Remote(server).collectionNames(), "no reason to retry yet")

                    if (release == "remote change") Remote(server).createCollection("Other") else device.now += Pusher.UNIQUE_HOLD_MILLIS

                    assertEquals(emptyList(), device.settle().held, release)
                    assertTrue("Study" in Remote(server).collectionNames(), release)
                }
            }
        }
    }

    @Test
    fun `a 404 on an update recreates the item`() = server().use { server ->
        Device(server).use { device ->
            device.sessions.addSession(2, 255, t(0))
            device.settle()
            server.rejectMutation = {
                if (it.str("type") == "UPDATE") HttpError(404, "NotFoundError", "Reading session not found") else null
            }
            device.sessions.addSession(2, 255, t(5))

            device.settle()

            assertEquals(listOf(200, 404, 200), server.postStatuses)
            assertEquals("CREATE READING_SESSION", server.mutationTypesOfLastPost().single())
        }
    }

    /**
     * With an index the 403 is the item's: revert it. Without one it may be a scope error: stop and keep the change,
     * and retry only after a long backoff.
     */
    @Test
    fun `a forbidden rename is reverted with an index and stops without one`() = server().use { server ->
        Device(server).use { device ->
            val study = device.collections.addCollection("Study", t(0)).localId
            device.settle()
            server.rejectMutation = { if (it.str("type") == "UPDATE") HttpError(403, "ForbiddenError", "no") else null }
            device.collections.renameCollection(study, "Memorize", t(5))

            val result = device.sync()

            if (withIndex) {
                assertIs<SyncResult.Success>(result)
                assertEquals(setOf("Study"), device.collectionNames())
            } else {
                val failed = assertIs<SyncResult.Failed>(result)
                assertTrue(failed.retryable)
                assertEquals(Pusher.FORBIDDEN_BACKOFF_MILLIS, failed.retryAfterMillis)
                assertEquals(setOf("Memorize"), device.collectionNames())
            }
            assertEquals(emptyList(), device.engine.held())
        }
    }

}

class FailureHandlingWithIndexTest : FailureHandlingTest(withIndex = true)

class FailureHandlingWithoutIndexTest : FailureHandlingTest(withIndex = false)
