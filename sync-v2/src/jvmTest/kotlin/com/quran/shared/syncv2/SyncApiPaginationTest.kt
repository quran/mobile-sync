package com.quran.shared.syncv2

import com.quran.shared.syncv2.fake.FakeSyncServer
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SyncApiPaginationTest {
    @Test
    fun `a pull completes against a server requiring pinned continuations`() = runBlocking {
        FakeSyncServer().use { server ->
            val remote = Remote(server)
            val ids = (1..4).map { remote.createSession(1, it, 1_760_000_000_000L + it) }.toSet()
            val http = HttpClient(OkHttp)
            try {
                val api = SyncApi(http, server.url, pageSize = 2) {
                    mapOf("Authorization" to "Bearer ${Device.ACCOUNT}")
                }

                val changes = api.get(0)

                assertEquals(ids, changes.entries.filter { it.resource == "READING_SESSION" }.map { it.resourceId }.toSet())
                assertEquals(server.account(Device.ACCOUNT).head, changes.head)
                assertEquals(3, server.getCount)
            } finally {
                http.close()
            }
        }
    }

    @Test
    fun `continuations pin the first body head including zero and retain the original query`() = runBlocking {
        for (head in listOf(0L, 91L)) {
            withApi(answer = { requests ->
                page(head, requests.size < 3, "row-${requests.size}")
            }) { api, requests ->
                val changes = api.get(42)

                assertEquals(head, changes.head)
                assertEquals(listOf("row-1", "row-2", "row-3"), changes.entries.map { it.resourceId })
                assertEquals(listOf("1", "2", "3"), requests.map { it.query["page"] })
                assertEquals(listOf(null, "$head", "$head"), requests.map { it.query["lastMutationAt"] })
                assertEquals(setOf("42"), requests.map { it.query["mutationsSince"] }.toSet())
                assertEquals(setOf("2"), requests.map { it.query["limit"] }.toSet())
            }
        }
    }

    @Test
    fun `a continuation 409 discards partial entries and restarts at the original watermark`() = runBlocking {
        for (gateway in listOf(false, true)) {
            withApi(answer = { requests ->
                when (requests.size) {
                    1 -> page(91, true, "discarded")
                    2 -> conflict(gateway)
                    3 -> page(92, true, "first")
                    else -> page(92, false, "second")
                }
            }) { api, requests ->
                val changes = api.get(42)

                assertEquals(92L, changes.head)
                assertEquals(listOf("first", "second"), changes.entries.map { it.resourceId })
                assertEquals(listOf("1", "2", "1", "2"), requests.map { it.query["page"] })
                assertEquals(listOf(null, "91", null, "92"), requests.map { it.query["lastMutationAt"] })
                assertEquals(setOf("42"), requests.map { it.query["mutationsSince"] }.toSet())
                assertEquals(setOf("2"), requests.map { it.query["limit"] }.toSet())
            }
        }
    }

    @Test
    fun `a page one 409 restarts with no stale continuation head`() = runBlocking {
        withApi(answer = { requests ->
            if (requests.size == 1) conflict() else page(93, false, "fresh")
        }) { api, requests ->
            assertEquals(listOf("fresh"), api.get(42).entries.map { it.resourceId })
            assertEquals(listOf("1", "1"), requests.map { it.query["page"] })
            assertEquals(listOf(null, null), requests.map { it.query["lastMutationAt"] })
            assertEquals(setOf("42"), requests.map { it.query["mutationsSince"] }.toSet())
        }
    }

    @Test
    fun `conflicts on page one or a continuation exhaust exactly three attempts`() = runBlocking {
        for (rejectedPage in listOf("1", "2")) {
            withApi(answer = { requests ->
                if (requests.last().query["page"] == rejectedPage) conflict() else page(91, true, "partial")
            }) { api, requests ->
                assertFailsWith<HeadChangedException> { api.get(42) }
                val attempt = if (rejectedPage == "1") listOf("1") else listOf("1", "2")
                assertEquals(List(3) { attempt }.flatten(), requests.map { it.query["page"] })
                assertEquals(setOf("42"), requests.map { it.query["mutationsSince"] }.toSet())
            }
        }
    }

    @Test
    fun `a successful response with a different head still restarts the pull`() = runBlocking {
        withApi(answer = { requests ->
            when (requests.size) {
                1 -> page(91, true, "discarded")
                2 -> page(92, false, "also discarded")
                else -> page(93, false, "fresh")
            }
        }) { api, requests ->
            assertEquals(listOf("fresh"), api.get(42).entries.map { it.resourceId })
            assertEquals(listOf("1", "2", "1"), requests.map { it.query["page"] })
            assertEquals(listOf(null, "91", null), requests.map { it.query["lastMutationAt"] })
        }
    }

    @Test
    fun `non conflict errors propagate without restarting either page`() = runBlocking {
        for (status in listOf(401, 403, 422, 429, 500, 503)) {
            for (failedPage in listOf(1, 2)) {
                withApi(answer = { requests ->
                    if (requests.size == failedPage) Reply(status, """{"error":{"code":"OtherError","message":"Not a head conflict"}}""")
                    else page(91, true, "partial")
                }) { api, requests ->
                    val error = assertFailsWith<SyncHttpException> { api.get(42) }
                    assertEquals(status, error.status)
                    assertEquals("OtherError", error.code)
                    assertEquals(failedPage, requests.size)
                }
            }
        }
    }

    @Test
    fun `post 409 is left to the existing push recovery`() = runBlocking {
        withApi(answer = { conflict(gateway = true) }) { api, requests ->
            val error = assertFailsWith<SyncHttpException> { api.post(42, emptyList()) }
            assertEquals(409, error.status)
            assertEquals("OutOfSyncError", error.code)
            assertEquals(listOf("POST"), requests.map { it.method })
        }
    }

    @Test
    fun `cancellation while requesting continuation headers is not retried`() = runBlocking {
        val cancellation = CancellationException("Cancelled pull")
        var headerRequests = 0
        withApi(
            authHeaders = { if (++headerRequests == 2) throw cancellation else emptyMap() },
            answer = { page(91, true, "partial") }
        ) { api, requests ->
            assertSame(cancellation, assertFailsWith<CancellationException> { api.get(42) })
            assertEquals(2, headerRequests)
            assertEquals(1, requests.size)
        }
    }

    private data class Request(val method: String, val query: Map<String, String>)
    private data class Reply(val status: Int, val body: String)

    /** Script only transport responses; the resource fakes above and in sync-pipelines enforce the backend rules. */
    private suspend fun withApi(
        authHeaders: suspend () -> Map<String, String> = { emptyMap() },
        answer: (List<Request>) -> Reply,
        block: suspend (SyncApi, List<Request>) -> Unit
    ) {
        val requests = mutableListOf<Request>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/sync") { exchange ->
            val query = exchange.requestURI.query.split("&").associate {
                val parts = it.split("=", limit = 2)
                parts[0] to parts[1]
            }
            requests += Request(exchange.requestMethod, query)
            val reply = answer(requests.toList())
            val bytes = reply.body.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            // Deliberately different: only the response body's head may pin a continuation.
            exchange.responseHeaders.add("X-Mutation-At", "999999")
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val http = HttpClient(OkHttp) { engine { config { retryOnConnectionFailure(false) } } }
        try {
            block(SyncApi(http, "http://127.0.0.1:${server.address.port}", 2, authHeaders), requests)
        } finally {
            http.close()
            server.stop(0)
        }
    }

    private fun page(head: Long, more: Boolean, vararg ids: String) = Reply(200, buildJsonObject {
        put("success", true)
        put("data", buildJsonObject {
            put("lastMutationAt", head)
            put("hasMore", more)
            put("mutations", buildJsonArray {
                ids.forEach { id ->
                    add(buildJsonObject {
                        put("resource", "NOTE")
                        put("resourceId", id)
                        put("type", "CREATE")
                        put("data", buildJsonObject {})
                        put("timestamp", head)
                    })
                }
            })
        })
    }.toString())

    private fun conflict(gateway: Boolean = false): Reply {
        val backend = """{"success":false,"error":{"code":"OutOfSyncError","message":"Head changed","details":{}}}"""
        return Reply(409, if (gateway) """{"success":false,"message":"Head changed","details":$backend}""" else backend)
    }
}
