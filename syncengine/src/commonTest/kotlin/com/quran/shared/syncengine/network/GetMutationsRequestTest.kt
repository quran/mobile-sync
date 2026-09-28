package com.quran.shared.syncengine.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
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
    fun `sends pagination and filter parameters`() = runTest {
        val backend = FakeSyncBackend(mutationCount = 1)

        backend.request().getMutations(
            lastModificationDate = 42L,
            authHeaders = mapOf("Authorization" to "Bearer token"),
            resources = listOf("BOOKMARK", "NOTE")
        )

        val parameters = backend.requests.single().url.parameters
        assertEquals("42", parameters["mutationsSince"])
        assertEquals("BOOKMARK,NOTE", parameters["resources"])
        assertEquals("1", parameters["page"])
        assertEquals(GetMutationsRequest.PAGE_LIMIT.toString(), parameters["limit"])
    }

    @Test
    fun `collects every page in order`() = runTest {
        val backend = FakeSyncBackend(mutationCount = 2 * GetMutationsRequest.PAGE_LIMIT + 500)

        val response = backend.request().getMutations(0L, emptyMap())

        assertEquals(listOf(1, 2, 3), backend.requestedPages)
        assertEquals(backend.resourceIds, response.mutations.map { it.resourceId })
        assertEquals(backend.head, response.lastModificationDate)
    }

    @Test
    fun `treats a response without pagination fields as a single page`() = runTest {
        val backend = FakeSyncBackend(mutationCount = 3, includePaginationFields = false)

        val response = backend.request().getMutations(0L, emptyMap())

        assertEquals(listOf(1), backend.requestedPages)
        assertEquals(3, response.mutations.size)
    }

    @Test
    fun `restarts from the first page when the head changes between pages`() = runTest {
        val backend = FakeSyncBackend(mutationCount = GetMutationsRequest.PAGE_LIMIT + 1)
        backend.onRequest = { requestIndex -> if (requestIndex == 1) backend.head += 1 }

        val response = backend.request().getMutations(0L, emptyMap())

        assertEquals(listOf(1, 2, 1, 2), backend.requestedPages)
        assertEquals(backend.resourceIds, response.mutations.map { it.resourceId })
        assertEquals(backend.head, response.lastModificationDate)
    }

    @Test
    fun `fails when the head keeps changing between pages`() = runTest {
        val backend = FakeSyncBackend(mutationCount = GetMutationsRequest.PAGE_LIMIT + 1)
        backend.onRequest = { backend.head += 1 }

        assertFailsWith<IllegalStateException> {
            backend.request().getMutations(0L, emptyMap())
        }

        assertEquals(List(GetMutationsRequest.MAX_SNAPSHOT_ATTEMPTS) { listOf(1, 2) }.flatten(), backend.requestedPages)
    }

    @Test
    fun `retries a later page after a server error without refetching earlier pages`() = runTest {
        val backend = FakeSyncBackend(mutationCount = 2 * GetMutationsRequest.PAGE_LIMIT + 1)
        backend.failure = { requestIndex ->
            if (requestIndex == 1) FakeFailure.Status(HttpStatusCode.ServiceUnavailable) else null
        }

        val response = backend.request().getMutations(0L, emptyMap())

        assertEquals(listOf(1, 2, 2, 3), backend.requestedPages)
        assertEquals(backend.resourceIds, response.mutations.map { it.resourceId })
    }

    @Test
    fun `retries a later page after an IO failure`() = runTest {
        val backend = FakeSyncBackend(mutationCount = GetMutationsRequest.PAGE_LIMIT + 1)
        backend.failure = { requestIndex -> if (requestIndex == 1) FakeFailure.Io else null }

        val response = backend.request().getMutations(0L, emptyMap())

        assertEquals(listOf(1, 2, 2), backend.requestedPages)
        assertEquals(backend.resourceIds, response.mutations.map { it.resourceId })
    }

    @Test
    fun `fails after exhausting retries for a later page`() = runTest {
        val backend = FakeSyncBackend(mutationCount = GetMutationsRequest.PAGE_LIMIT + 1)
        backend.failure = { requestIndex ->
            if (requestIndex >= 1) FakeFailure.Status(HttpStatusCode.BadGateway) else null
        }

        val exception = assertFailsWith<SyncNetworkException> {
            backend.request().getMutations(0L, emptyMap())
        }

        assertEquals(HttpStatusCode.BadGateway, exception.status)
        assertEquals(listOf(1) + List(GetMutationsRequest.MAX_PAGE_RETRIES + 1) { 2 }, backend.requestedPages)
    }

    @Test
    fun `does not retry a later page after a client error`() = runTest {
        val backend = FakeSyncBackend(mutationCount = GetMutationsRequest.PAGE_LIMIT + 1)
        backend.failure = { requestIndex ->
            if (requestIndex == 1) FakeFailure.Status(HttpStatusCode.Unauthorized) else null
        }

        assertFailsWith<SyncNetworkException> {
            backend.request().getMutations(0L, emptyMap())
        }

        assertEquals(listOf(1, 2), backend.requestedPages)
    }

    @Test
    fun `does not retry the first page`() = runTest {
        val backend = FakeSyncBackend(mutationCount = 1)
        backend.failure = { FakeFailure.Status(HttpStatusCode.ServiceUnavailable) }

        assertFailsWith<SyncNetworkException> {
            backend.request().getMutations(0L, emptyMap())
        }

        assertEquals(listOf(1), backend.requestedPages)
    }

    @Test
    fun `fails when an empty page reports more pages`() = runTest {
        val backend = FakeSyncBackend(mutationCount = 0)
        backend.hasMoreOverride = true

        assertFailsWith<IllegalStateException> {
            backend.request().getMutations(0L, emptyMap())
        }

        assertEquals(listOf(1), backend.requestedPages)
    }

    @Test
    fun `fails when pages continue past the reported total`() = runTest {
        val backend = FakeSyncBackend(mutationCount = GetMutationsRequest.PAGE_LIMIT + 1)
        backend.hasMoreOverride = true
        backend.totalOverride = 1

        assertFailsWith<IllegalStateException> {
            backend.request().getMutations(0L, emptyMap())
        }

        assertEquals(listOf(1, 2), backend.requestedPages)
    }
}

private sealed interface FakeFailure {
    data class Status(val status: HttpStatusCode) : FakeFailure
    data object Io : FakeFailure
}

/** Serves `/v1/sync` pages the way the backend does: offset pages ordered by change time. */
private class FakeSyncBackend(
    mutationCount: Int,
    private val includePaginationFields: Boolean = true
) {
    val resourceIds = List(mutationCount) { index -> "bookmark-$index" }
    var head = 10_000L
    var onRequest: (requestIndex: Int) -> Unit = {}
    var failure: (requestIndex: Int) -> FakeFailure? = { null }
    var hasMoreOverride: Boolean? = null
    var totalOverride: Int? = null
    val requests = mutableListOf<HttpRequestData>()
    val requestedPages: List<Int>
        get() = requests.map { request -> request.url.parameters["page"]!!.toInt() }

    fun request(): GetMutationsRequest {
        val client = HttpClient(MockEngine { request -> respondTo(request) }) {
            install(ContentNegotiation) {
                json(
                    Json {
                        explicitNulls = false
                        ignoreUnknownKeys = true
                    }
                )
            }
        }
        return GetMutationsRequest(client, "https://example.test")
    }

    private fun MockRequestHandleScope.respondTo(request: HttpRequestData): HttpResponseData {
        val requestIndex = requests.size
        requests += request
        onRequest(requestIndex)
        when (val failure = failure(requestIndex)) {
            FakeFailure.Io -> throw IOException("Connection reset")
            is FakeFailure.Status -> return respond(
                content = """{"success":false,"error":{"code":"Error","message":"failure"}}""",
                status = failure.status,
                headers = jsonHeaders
            )
            null -> Unit
        }

        val page = request.url.parameters["page"]!!.toInt()
        val limit = request.url.parameters["limit"]!!.toInt()
        val pageIds = resourceIds.drop((page - 1) * limit).take(limit)
        val total = totalOverride ?: resourceIds.size
        val hasMore = hasMoreOverride ?: (page * limit < resourceIds.size)
        val mutations = pageIds.joinToString(",") { resourceId ->
            """{"resource":"BOOKMARK","resourceId":"$resourceId","type":"CREATE","data":{},"timestamp":1}"""
        }
        val pagination = if (includePaginationFields) {
            ""","page":$page,"limit":$limit,"total":$total,"hasMore":$hasMore"""
        } else {
            ""
        }
        return respond(
            content = """{"success":true,"data":{"lastMutationAt":$head,"mutations":[$mutations]$pagination}}""",
            status = HttpStatusCode.OK,
            headers = jsonHeaders
        )
    }

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }
}
