package com.quran.shared.syncengine.network

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GetMutationsRequestPaginationTest {

    @Test
    fun `cursor pagination aggregates every page with a stable snapshot`() = runTest {
        TestServer(
            listOf(
                Response(
                    body = syncResponse(
                        mutationId = "remote-1",
                        hasMore = true,
                        page = 1,
                        syncUntil = 2_000,
                        nextCursor = "cursor-1"
                    )
                ),
                Response(
                    body = syncResponse(
                        mutationId = "remote-2",
                        hasMore = false,
                        syncUntil = 2_000
                    )
                )
            )
        ).use { server ->
            val client = HttpClientFactory.createHttpClient()
            try {
                val response = GetMutationsRequest(client, server.baseUrl).getMutations(
                    lastModificationDate = 100,
                    authHeaders = mapOf("x-auth-token" to "token"),
                    resources = listOf("BOOKMARK", "COLLECTION")
                )

                assertEquals(listOf("remote-1", "remote-2"), response.mutations.map { it.resourceId })
                assertEquals(2_000, response.lastModificationDate)
                assertEquals(
                    mapOf(
                        "mutationsSince" to "100",
                        "resources" to "BOOKMARK,COLLECTION"
                    ),
                    server.queries[0]
                )
                assertEquals(
                    mapOf(
                        "mutationsSince" to "100",
                        "resources" to "BOOKMARK,COLLECTION",
                        "syncUntil" to "2000",
                        "cursor" to "cursor-1"
                    ),
                    server.queries[1]
                )
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `legacy pagination keeps the original timestamp and increments page`() = runTest {
        TestServer(
            listOf(
                Response(
                    body = syncResponse(
                        mutationId = "remote-1",
                        hasMore = true,
                        page = 1
                    )
                ),
                Response(
                    body = syncResponse(
                        mutationId = "remote-2",
                        hasMore = false,
                        page = 2
                    )
                )
            )
        ).use { server ->
            val client = HttpClientFactory.createHttpClient()
            try {
                val response = GetMutationsRequest(client, server.baseUrl).getMutations(
                    lastModificationDate = 321,
                    authHeaders = emptyMap(),
                    resources = listOf("BOOKMARK")
                )

                assertEquals(listOf("remote-1", "remote-2"), response.mutations.map { it.resourceId })
                assertEquals("321", server.queries[1]["mutationsSince"])
                assertEquals("2", server.queries[1]["page"])
                assertFalse(server.queries[1].containsKey("syncUntil"))
                assertFalse(server.queries[1].containsKey("cursor"))
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `failure on a later page fails the whole fetch`() = runTest {
        TestServer(
            listOf(
                Response(
                    body = syncResponse(
                        mutationId = "remote-1",
                        hasMore = true,
                        page = 1,
                        syncUntil = 2_000,
                        nextCursor = "cursor-1"
                    )
                ),
                Response(
                    status = 500,
                    body = """{"success":false,"type":"server_error","message":"page failed"}"""
                )
            )
        ).use { server ->
            val client = HttpClientFactory.createHttpClient()
            try {
                assertFailsWith<Exception> {
                    GetMutationsRequest(client, server.baseUrl).getMutations(
                        lastModificationDate = 100,
                        authHeaders = emptyMap()
                    )
                }
                assertEquals(2, server.queries.size)
            } finally {
                client.close()
            }
        }
    }

    private fun syncResponse(
        mutationId: String,
        hasMore: Boolean,
        page: Int? = null,
        syncUntil: Long? = null,
        nextCursor: String? = null
    ): String {
        val paginationFields = buildList {
            page?.let { add(""""page":$it""") }
            add(""""hasMore":$hasMore""")
            syncUntil?.let { add(""""syncUntil":$it""") }
            nextCursor?.let { add(""""nextCursor":"$it"""") }
        }.joinToString(",")
        return """
            {
              "success": true,
              "data": {
                "lastMutationAt": ${syncUntil ?: 2_000},
                "mutations": [{
                  "resource": "BOOKMARK",
                  "resourceId": "$mutationId",
                  "type": "CREATE",
                  "data": {"type": "PAGE", "page": 1},
                  "timestamp": 1000
                }],
                $paginationFields
              }
            }
        """.trimIndent()
    }
}

private data class Response(
    val status: Int = 200,
    val body: String
)

private class TestServer(
    private val responses: List<Response>
) : AutoCloseable {
    private val requestIndex = AtomicInteger(0)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val queries = mutableListOf<Map<String, String>>()
    val baseUrl: String

    init {
        server.createContext("/v1/sync") { exchange ->
            handle(exchange)
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    private fun handle(exchange: HttpExchange) {
        queries += parseQuery(exchange.requestURI.rawQuery)
        val response = responses[requestIndex.getAndIncrement()]
        val body = response.body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(response.status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        return rawQuery.split("&").associate { entry ->
            val parts = entry.split("=", limit = 2)
            URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()) to
                URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8.name())
        }
    }

    override fun close() {
        server.stop(0)
    }
}
