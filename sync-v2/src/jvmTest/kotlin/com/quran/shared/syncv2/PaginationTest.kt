package com.quran.shared.syncv2

import com.quran.shared.syncv2.fake.FakeSyncServer
import com.quran.shared.syncv2.fake.HttpError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * GET paging against the backend's continuation guard: a page after the first must send page 1's head as
 * `lastMutationAt`, and a 409 `OutOfSyncError` on any page restarts the fetch from page 1 with the same token.
 */
class PaginationTest {
    private fun t(n: Int) = 1_760_000_000_000L + n * 1_000L

    /** Syncs one session, so the next fetch starts from a nonzero token, then writes [count] more remotely. */
    private fun Device.syncedWithRemoteSessions(count: Int): Long {
        val remote = Remote(server)
        remote.createSession(1, 1, t(0))
        syncOk()
        repeat(count) { remote.createSession(2, it + 1, t(it + 1)) }
        server.getQueries.clear()
        return engine.store.token
    }

    private fun FakeSyncServer.pages() = getQueries.map { it.getValue("page") }

    @Test
    fun `later pages send page 1's head and page 1 sends none`() = FakeSyncServer().use { server ->
        Device(server, pageSize = 2).use { device ->
            val token = device.syncedWithRemoteSessions(5)
            val head = server.account(Device.ACCOUNT).head

            device.syncOk()

            assertEquals(listOf("1", "2", "3"), server.pages())
            assertNull(server.getQueries.first()["lastMutationAt"], "page 1 sends no continuation head")
            assertEquals(listOf("$head", "$head"), server.getQueries.drop(1).map { it["lastMutationAt"] })
            assertEquals(listOf("$token"), server.getQueries.map { it["mutationsSince"] }.distinct())
            assertEquals(listOf("2"), server.getQueries.map { it["limit"] }.distinct())
            assertEquals(head, device.engine.store.token)
        }
    }

    /** A write that commits while page 1 is read: the backend answers page 1 with the same 409. */
    @Test
    fun `a 409 on page 1 restarts the fetch`() = FakeSyncServer().use { server ->
        Device(server, pageSize = 2).use { device ->
            val token = device.syncedWithRemoteSessions(3)
            var rejected = false
            server.rejectGet = { page ->
                if (page == 1 && !rejected) HttpError(409, SyncErrors.OUT_OF_SYNC, "Client is out of sync").also { rejected = true } else null
            }

            device.syncOk()

            assertEquals(listOf("1", "1", "2"), server.pages())
            assertEquals(listOf("$token"), server.getQueries.map { it["mutationsSince"] }.distinct())
            assertEquals(setOf("1:1", "2:1", "2:2", "2:3"), device.sessionVerses().toSet())
        }
    }

    /** Only a 409 OutOfSyncError means the head moved; anything else on page 2 ends the fetch unchanged. */
    @Test
    fun `other errors on a later page are not retried as a head change`() = FakeSyncServer().use { server ->
        Device(server, pageSize = 2).use { device ->
            val token = device.syncedWithRemoteSessions(3)
            listOf(
                HttpError(500, "ServerError", "Something went wrong") to true,
                HttpError(422, "ValidationError", "\"limit\" must be a number") to false,
                HttpError(409, SyncErrors.UNIQUE_VIOLATION, "Duplicate") to false
            ).forEach { (error, retryable) ->
                server.getQueries.clear()
                server.rejectGet = { page -> if (page == 2) error else null }

                val failed = assertIs<SyncResult.Failed>(device.sync())

                val cause = assertIs<SyncHttpException>(failed.cause, failed.reason)
                assertEquals(error.status to error.code, cause.status to cause.code)
                assertEquals(retryable, failed.retryable, failed.reason)
                assertEquals(listOf("1", "2"), server.pages(), "${error.status} ${error.code}: no restart")
                assertEquals(listOf("1:1"), device.sessionVerses(), "no page was merged")
                assertEquals(token, device.engine.store.token)
            }
        }
    }
}
