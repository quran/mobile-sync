package com.quran.shared.syncengine.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GetMutationsRequestTest {

    @Test
    fun `fetches every page in order`() = runTest {
        val backend = FakeSyncBackend(mutationCount = 2 * GetMutationsRequest.PAGE_LIMIT + 1)

        val response = backend.request().getMutations(0L, emptyMap())

        assertEquals(listOf(1, 2, 3), backend.requestedPages)
        assertEquals(backend.resourceIds, response.mutations.map { it.resourceId })
        assertEquals(backend.head, response.lastModificationDate)
    }

    @Test
    fun `fails when the sync head changes between pages`() = runTest {
        val backend = FakeSyncBackend(mutationCount = GetMutationsRequest.PAGE_LIMIT + 1)
        backend.onRequest = { index ->
            if (index == 1) backend.head += 1
            HttpStatusCode.OK
        }

        assertFailsWith<IllegalStateException> { backend.request().getMutations(0L, emptyMap()) }
    }

    @Test
    fun `retries a later page after transient failures`() = runTest {
        val backend = FakeSyncBackend(mutationCount = 2 * GetMutationsRequest.PAGE_LIMIT + 1)
        backend.onRequest = { index ->
            when (index) {
                1 -> HttpStatusCode.ServiceUnavailable
                2 -> throw IOException("Connection reset")
                else -> HttpStatusCode.OK
            }
        }

        val response = backend.request().getMutations(0L, emptyMap())

        assertEquals(listOf(1, 2, 2, 2, 3), backend.requestedPages)
        assertEquals(backend.resourceIds, response.mutations.map { it.resourceId })
    }

    @Test
    fun `fails after exhausting retries for a later page`() = runTest {
        val backend = FakeSyncBackend(mutationCount = GetMutationsRequest.PAGE_LIMIT + 1)
        backend.onRequest = { index -> if (index == 0) HttpStatusCode.OK else HttpStatusCode.BadGateway }

        assertFailsWith<SyncNetworkException> { backend.request().getMutations(0L, emptyMap()) }

        assertEquals(listOf(1, 2, 2, 2, 2), backend.requestedPages)
    }

    @Test
    fun `does not retry a later page after a client error`() = runTest {
        val backend = FakeSyncBackend(mutationCount = GetMutationsRequest.PAGE_LIMIT + 1)
        backend.onRequest = { index -> if (index == 1) HttpStatusCode.Unauthorized else HttpStatusCode.OK }

        assertFailsWith<SyncNetworkException> { backend.request().getMutations(0L, emptyMap()) }

        assertEquals(listOf(1, 2), backend.requestedPages)
    }
}

private class FakeSyncBackend(mutationCount: Int) {
    val resourceIds = List(mutationCount) { "bookmark-$it" }
    var head = 10_000L
    var onRequest: (index: Int) -> HttpStatusCode = { HttpStatusCode.OK }
    val requestedPages = mutableListOf<Int>()

    fun request() = GetMutationsRequest(
        HttpClient(
            MockEngine { request ->
                val page = request.url.parameters["page"]?.toInt() ?: 1
                val limit = request.url.parameters["limit"]?.toInt() ?: 100
                requestedPages += page
                val status = onRequest(requestedPages.lastIndex)
                val mutations = resourceIds.drop((page - 1) * limit).take(limit).joinToString(",") {
                    """{"resource":"BOOKMARK","resourceId":"$it","type":"CREATE","timestamp":1}"""
                }
                respond(
                    content = """{"success":true,"data":{"lastMutationAt":$head,"mutations":[$mutations],""" +
                        """"total":${resourceIds.size}}}""",
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        },
        "https://example.test"
    )
}
