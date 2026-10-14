package com.quran.shared.syncv2

import com.quran.shared.syncv2.fake.FakeSyncServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Single-flight sync, and wipes (logout, account switch) racing a sync in flight. */
class ConcurrencyTest {
    private fun t(n: Int) = 1_760_000_000_000L + n * 1_000L

    @Test
    fun `two concurrent syncs run one after the other`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.collections.addCollection("Study", t(0))
            device.sessions.addSession(1, 1, t(1))

            val results = runBlocking(Dispatchers.IO) {
                List(2) { async { device.engine.sync(device.account) } }.awaitAll()
            }

            results.forEach { assertIs<SyncResult.Success>(it) }
            assertEquals(listOf("GET", "POST", "GET"), server.requests, "the second sync starts after the first")
            assertEquals(listOf(200), server.postStatuses)
            assertEquals(setOf("Study"), Remote(server).collectionNames())
        }
    }

    /** The user keeps editing during every POST: each sync makes progress, fails as retryable, and the last one settles. */
    @Test
    fun `continuous edits keep syncing instead of stopping for good`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val study = device.collections.addCollection("Name 0", t(0)).localId
            var edits = 0
            fun editDuringPost() {
                server.duringNextPost = {
                    edits += 1
                    device.collections.renameCollection(study, "Name $edits", t(edits))
                    if (edits < 80) editDuringPost()
                }
            }
            editDuringPost()

            val first = assertIs<SyncResult.Failed>(device.sync())

            assertEquals(SyncEngineV2.MORE_PENDING, first.reason)
            assertTrue(first.retryable)
            assertEquals(SyncEngineV2.MAX_ROUNDS * Pusher.MAX_PHASES, first.pushed, "one change pushed in every phase of every round")
            device.settle()
            assertEquals(setOf("Name 80"), device.collectionNames())
            assertEquals(setOf("Name 80"), Remote(server).collectionNames())
        }
    }

    /** A sync queued behind another, then a logout: the queued sync must not download the account again. */
    @Test
    fun `a sync queued before a wipe does not run`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            Remote(server).createCollection("Study")
            val release = CompletableDeferred<Unit>()
            server.duringNextGet = { runBlocking { release.await() } }

            val results = runBlocking(Dispatchers.IO) {
                val first = async { device.engine.sync(device.account) }
                while (server.getCount == 0) delay(5)
                // Undispatched: the call reads the wipe epoch and queues on the mutex before the wipe below.
                val queued = async(start = CoroutineStart.UNDISPATCHED) { device.engine.sync(device.account) }
                device.engine.wipe()
                release.complete(Unit)
                listOf(first.await(), queued.await())
            }

            results.forEach { assertIs<SyncResult.Failed>(it) }
            assertEquals(1, server.getCount, "the queued sync made no GET")
            assertEquals(emptySet(), device.collectionNames())
            assertNull(device.engine.store.account)
        }
    }

    /** A logout while the GET is in flight: the sync must not write the account's data into the wiped database. */
    @Test
    fun `a wipe during the fetch is not undone by the merge`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.collections.addCollection("Study", t(0))
            device.settle()
            Remote(server).createCollection("Memorize")
            server.duringNextGet = { device.engine.wipe() }

            val failed = assertIs<SyncResult.Failed>(device.sync())

            assertTrue(failed.retryable)
            assertEquals(emptySet(), device.collectionNames())
            assertTrue(device.engine.store.rows("COLLECTION").isEmpty())
            assertEquals(0L, device.engine.store.token)
            assertNull(device.engine.store.account)
        }
    }

    /** A logout while the POST is in flight: its settle must not write bases (or an outbox) into the wiped database. */
    @Test
    fun `a wipe during the push is not undone by its settle`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.collections.addCollection("Study", t(0))
            server.duringNextPost = { device.engine.wipe() }

            assertIs<SyncResult.Failed>(device.sync())

            assertEquals(emptySet(), device.collectionNames())
            assertTrue(device.engine.store.rows("COLLECTION").isEmpty())
            assertEquals(emptyList(), device.engine.store.outboxes)
            assertNull(device.engine.store.account)
        }
    }

    /** S3 with the race: A's sync in flight while the user switches to B. B must never see A's data. */
    @Test
    fun `an account switch during a sync keeps the old account's data out of the new one`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.collections.addCollection("A's", t(0))
            device.settle()
            device.collections.addCollection("A pending", t(1))
            Remote(server, "user-b").createCollection("B's")
            server.duringNextGet = { device.engine.wipe() }
            device.sync()

            device.account = "user-b"
            device.settle()

            assertEquals(setOf("B's"), device.collectionNames())
            assertEquals(setOf("B's"), Remote(server, "user-b").collectionNames())
            assertEquals(setOf("A's"), Remote(server, "user-a").collectionNames())
        }
    }
}
