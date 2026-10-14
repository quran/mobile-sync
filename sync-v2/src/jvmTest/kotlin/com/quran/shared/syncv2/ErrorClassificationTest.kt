package com.quran.shared.syncv2

import com.quran.shared.syncv2.fake.FakeSyncServer
import com.quran.shared.syncv2.fake.HttpError
import com.quran.shared.syncv2.fake.str
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Only an allowlist of item errors holds; auth, rate limits and outages abort, retry and hold nothing.
 * Runs against a server with and without `mutationIndex`: see the subclasses at the end.
 */
abstract class ErrorClassificationTest(private val withIndex: Boolean) {
    private fun t(n: Int) = 1_760_000_000_000L + n * 1_000L

    private fun server() = FakeSyncServer().apply { includeMutationIndex = withIndex }

    private fun Device.addWork() {
        sessions.addSession(1, 1, t(0))
        sessions.addSession(18, 1, t(1))
        collections.addCollection("Study", t(2))
    }

    private fun assertEverythingSynced(server: FakeSyncServer) {
        assertEquals(setOf("1:1", "18:1"), Remote(server).sessionVerses())
        assertEquals(setOf("Study"), Remote(server).collectionNames())
    }

    @Test
    fun `401, 408, 429 and gateway 5xx abort with a retryable failure and hold nothing`() {
        listOf(401 to "UnauthorizedError", 408 to "", 429 to "", 502 to "", 503 to "", 504 to "").forEach { (status, code) ->
            server().use { server ->
                server.rejectMutation = { HttpError(status, code, "unavailable") }
                Device(server).use { device ->
                    device.addWork()

                    val failed = assertIs<SyncResult.Failed>(device.sync(), "$status")
                    assertTrue(failed.retryable, "$status is retryable")
                    assertEquals(emptyList(), device.engine.held(), "$status holds nothing")
                    assertEquals(1, server.postStatuses.size, "$status is not bisected")

                    server.rejectMutation = null
                    device.settle()
                    assertEverythingSynced(server)
                }
            }
        }
    }

    /**
     * With `mutationIndex`, the backend wraps unexpected errors in the apply loop with an index: a deadlock is a 500
     * with an index.
     */
    @Test
    fun `a transient 500 is retried, not held`() = server().use { server ->
        var failures = 1
        server.rejectMutation = { if (failures-- > 0) HttpError(500, "ServerError", "deadlock") else null }
        Device(server).use { device ->
            device.addWork()

            val result = device.settle()

            assertEquals(emptyList(), result.held)
            assertEquals(listOf(500, 200), server.postStatuses)
            assertEverythingSynced(server)
        }
    }

    private fun heldUntil(device: Device, type: String) =
        device.engine.store.rows(type).values.single { it.heldReason != null }.heldUntil!! - device.now

    /**
     * A bad item, first at index 0 (with an index: a control POST of the rest proves it) and then at index 1 (the
     * backend flushed index 0, so no control is needed). Its hold grows from 1 h to 6 h to 24 h while it keeps failing.
     */
    @Test
    fun `an item that keeps failing with 500 is held for longer each time`() {
        listOf<Pair<String, (JsonObject) -> Boolean>>(
            "COLLECTION" to { it.str("resource") == "COLLECTION" },
            "READING_SESSION" to { it.isSessionAt(18, 1) }
        ).forEach { (type, isBad) ->
            server().use { server ->
                server.rejectMutation = { if (isBad(it)) HttpError(500, "ServerError", "bad row") else null }
                Device(server).use { device ->
                    device.addWork()

                    assertEquals(listOf("500 ServerError"), device.settle().held.map { it.reason }, type)
                    assertEquals(listOf(500, 500), server.postStatuses.take(2), "the first 500 is retried with a fresh fetch")
                    assertEquals(Pusher.HOLD_MILLIS[0], heldUntil(device, type))
                    device.now += Pusher.HOLD_MILLIS[0]
                    device.settle()
                    assertEquals(Pusher.HOLD_MILLIS[1], heldUntil(device, type), "longer the second time")
                    device.now += Pusher.HOLD_MILLIS[1]
                    device.settle()
                    assertEquals(Pusher.HOLD_MILLIS[2], heldUntil(device, type), "and the third")

                    server.rejectMutation = null
                    device.now += Pusher.HOLD_MILLIS[2] - 1
                    device.settle()
                    assertEquals(1, device.engine.held().size, "still held before it expires")
                    device.now += 1
                    assertEquals(emptyList(), device.settle().held)
                    assertEverythingSynced(server)
                    assertEquals(emptyMap(), device.engine.store.failures, "landing clears its failure count")
                }
            }
        }
    }

    /**
     * A 500 at index 0 on an item another change depends on: deleting X while Y takes the name X, or renaming X to Z
     * while a new X is created. Posted without the culprit, the dependent fails the unique check: the server
     * answered, so the culprit is held (expiring) and the dependent waits on it; the unrelated session lands.
     */
    @Test
    fun `a 500 on an item another change depends on holds that item, not the dependent`() {
        listOf("delete X, rename Y to X", "rename X to Z, create X").forEach { case ->
            server().use { server ->
                Device(server).use { device ->
                    val x = device.collections.addCollection("X", t(0)).localId
                    val y = device.collections.addCollection("Y", t(1)).localId
                    device.settle()
                    server.rejectMutation = {
                        val bad = if (case.startsWith("delete")) it.str("type") == "DELETE" else (it["data"] as? JsonObject)?.str("name") == "Z"
                        if (bad && it.str("resource") == "COLLECTION") HttpError(500, "ServerError", "bad row") else null
                    }
                    if (case.startsWith("delete")) {
                        device.collections.deleteCollection(x)
                        device.collections.renameCollection(y, "X", t(2))
                    } else {
                        device.collections.renameCollection(x, "Z", t(2))
                        device.collections.addCollection("X", t(3))
                    }
                    device.sessions.addSession(1, 1, t(4))

                    device.sync()
                    val held = device.settle().held

                    assertTrue(held.any { it.localId == x && it.reason == "500 ServerError" }, "$case: $held")
                    assertEquals(setOf("1:1"), Remote(server).sessionVerses(), case)
                    if (withIndex) {
                        assertTrue(held.any { it.reason == HeldItem.WAITING_ON_KEY && it.waitingOn == ItemRef("COLLECTION", x) }, "$case: $held")
                    }
                }
            }
        }
    }

    /** The server refuses every POST for several runs while GET works: no valid item is held, and all of it lands. */
    @Test
    fun `a POST outage across several runs holds nothing`() = server().use { server ->
        server.rejectMutation = { HttpError(500, "ServerError", "Something went wrong") }
        Device(server).use { device ->
            device.addWork()

            repeat(3) { run ->
                val posts = server.postStatuses.size
                val failed = assertIs<SyncResult.Failed>(device.sync(), "run $run")
                assertTrue(failed.retryable)
                assertEquals(emptyList(), device.engine.held(), "run $run holds nothing")
                assertTrue(server.postStatuses.size - posts <= 4, "no POST storm: ${server.postStatuses.drop(posts)}")
                device.now += Pusher.HOLD_MILLIS[0]
            }
            server.rejectMutation = null
            assertEquals(emptyList(), device.settle().held)
            assertEverythingSynced(server)
        }
    }

    /**
     * One pending item and a 500 before anything landed: it can't be told from an outage, so it is held only when
     * the next run fails the same way (for 6 h: its second failing run).
     */
    @Test
    fun `a lone item failing with 500 is held only in the second run`() = server().use { server ->
        server.rejectMutation = { HttpError(500, "ServerError", "bad row") }
        Device(server).use { device ->
            device.collections.addCollection("Study", t(0))

            assertIs<SyncResult.Failed>(device.sync())
            assertEquals(emptyList(), device.engine.held())
            assertEquals(listOf("500 ServerError"), device.syncOk().held.map { it.reason })

            server.rejectMutation = null
            device.now += Pusher.HOLD_MILLIS[1]
            device.settle()
            assertEquals(setOf("Study"), Remote(server).collectionNames())
        }
    }

    /**
     * A backend error off the allowlist (here a 404 on a CREATE) aborts the run; when the same item fails the same way
     * in the next run, it is held with an expiry, so it can't wedge every other change.
     */
    @Test
    fun `an item failing with the same off-list error in two runs is held, not wedging the rest`() = server().use { server ->
        server.rejectMutation = {
            if (it.str("resource") == "COLLECTION") HttpError(404, "NotFoundError", "Collection not found") else null
        }
        Device(server).use { device ->
            device.addWork()

            val failed = assertIs<SyncResult.Failed>(device.sync())
            assertTrue(failed.retryable)
            assertEquals(emptyList(), device.engine.held(), "one run proves nothing")

            assertEquals(listOf("404 NotFoundError"), device.settle().held.map { it.reason })
            assertEquals(setOf("1:1", "18:1"), Remote(server).sessionVerses(), "the rest is no longer wedged")
            assertEquals(Pusher.HOLD_MILLIS[1], heldUntil(device, "COLLECTION"), "its second failing run")

            server.rejectMutation = null
            device.now += Pusher.HOLD_MILLIS[1]
            device.settle()
            assertEverythingSynced(server)
        }
    }

    @Test
    fun `the failure count of an item that is gone is forgotten`() = server().use { server ->
        server.rejectMutation = {
            if (it.str("resource") == "COLLECTION") HttpError(404, "NotFoundError", "Collection not found") else null
        }
        Device(server).use { device ->
            val study = device.collections.addCollection("Study", t(0)).localId
            assertIs<SyncResult.Failed>(device.sync())
            assertEquals(setOf("COLLECTION:$study"), device.engine.store.failures.keys)

            device.collections.deleteCollection(study)
            device.settle()

            assertEquals(emptyMap(), device.engine.store.failures)
        }
    }

    @Test
    fun `holds are re-evaluated after an app update`() = server().use { server ->
        server.rejectMutation = { if (it.isSessionAt(18, 1)) HttpError(422, "ValidationError", "rejected") else null }
        Device(server).use { device ->
            device.addWork()
            assertEquals(listOf("422 ValidationError"), device.settle().held.map { it.reason })
            server.rejectMutation = null
            device.settle()
            assertEquals(setOf("1:1"), Remote(server).sessionVerses(), "a validation hold waits for an edit")

            device.upgrade("2")

            assertEquals(emptyList(), device.settle().held)
            assertEverythingSynced(server)
        }
    }
}

class ErrorClassificationWithIndexTest : ErrorClassificationTest(withIndex = true)

class ErrorClassificationWithoutIndexTest : ErrorClassificationTest(withIndex = false)
