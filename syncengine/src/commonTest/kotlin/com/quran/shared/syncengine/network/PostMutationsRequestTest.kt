@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine.network

import com.quran.shared.mutations.Mutation
import com.quran.shared.syncengine.SyncMutation
import com.quran.shared.syncengine.validatePushedMutationResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PostMutationsRequestTest {

    @Test
    fun `postMutations throws when successful HTTP response has success false envelope`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """
                        {
                          "success": false,
                          "data": {
                            "lastMutationAt": 1234,
                            "mutations": []
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
        ) {
            install(ContentNegotiation) {
                json(
                    Json {
                        explicitNulls = false
                        ignoreUnknownKeys = true
                    }
                )
            }
        }
        val request = PostMutationsRequest(client, "https://example.test")

        assertFailsWith<RuntimeException> {
            request.postMutations(
                mutations = listOf(
                    SyncMutation(
                        resource = "BOOKMARK",
                        resourceId = null,
                        mutation = Mutation.CREATED,
                        data = null,
                        timestamp = null
                    )
                ),
                lastModificationDate = 1000L,
                authHeaders = emptyMap()
            )
        }
    }

    @Test
    fun `postMutations preserves message from error response with nested details`() = runTest {
        val errorBody = """
            {
              "details": {
                "success": false,
                "error": {
                  "code": "ValidationError",
                  "message": "\"bookmarkId\" is not allowed",
                  "details": {}
                }
              },
              "message": "\"bookmarkId\" is not allowed",
              "type": "unprocessable_entity",
              "success": false,
              "requestId": "request-a"
            }
        """.trimIndent()
        val request = postRequestWithResponse(errorBody, HttpStatusCode.UnprocessableEntity)

        val exception = assertFailsWith<SyncNetworkException> {
            request.postMutations(
                mutations = listOf(collectionBookmarkCreateRequest()),
                lastModificationDate = 1000L,
                authHeaders = emptyMap()
            )
        }

        assertEquals("\"bookmarkId\" is not allowed", exception.parsedMessage)
        assertEquals(errorBody, exception.rawBody)

        val errorResponse = errorResponseJson.decodeFromString<SyncErrorResponse>(errorBody)
        assertEquals(buildJsonObject {}, errorResponse.details?.error?.details)
    }

    @Test
    fun `postMutations ignores unknown response fields`() = runTest {
        val localMutation = collectionBookmarkCreateRequest()
        val request = postRequestWithResponse(
            """
                {
                  "success": true,
                  "requestId": "request-a",
                  "data": {
                    "lastMutationAt": 1234,
                    "serverVersion": 2,
                    "mutations": [
                      {
                        "type": "CREATE",
                        "resource": "COLLECTION_BOOKMARK",
                        "traceId": "trace-a",
                        "data": {
                          "collectionId": "collection-a",
                          "bookmarkId": "bookmark-a",
                          "type": "ayah",
                          "key": 2,
                          "verseNumber": 255
                        },
                        "timestamp": 1234
                      }
                    ]
                  }
                }
            """.trimIndent()
        )

        val response = request.postMutations(listOf(localMutation), 1000L, emptyMap())

        assertEquals(null, response.mutations.single().resourceId)
        validatePushedMutationResponse(listOf(localMutation), response.mutations)
    }

    @Test
    fun `postMutations decodes collection bookmark create ACK with explicit null resource id`() = runTest {
        val localMutation = collectionBookmarkCreateRequest()
        val request = postRequestWithResponse(
            """
                {
                  "success": true,
                  "data": {
                    "lastMutationAt": 1234,
                    "mutations": [
                      {
                        "type": "CREATE",
                        "resource": "COLLECTION_BOOKMARK",
                        "resourceId": null,
                        "data": {
                          "collectionId": "collection-a",
                          "bookmarkId": "bookmark-a",
                          "type": "ayah",
                          "key": 2,
                          "verseNumber": 255
                        },
                        "timestamp": 1234
                      }
                    ]
                  }
                }
            """.trimIndent()
        )

        val response = request.postMutations(listOf(localMutation), 1000L, emptyMap())

        assertEquals(null, response.mutations.single().resourceId)
        validatePushedMutationResponse(listOf(localMutation), response.mutations)
    }

    @Test
    fun `postMutations keeps non collection ACK missing resource id rejected by validation`() = runTest {
        val localMutation = SyncMutation(
            resource = "BOOKMARK",
            resourceId = null,
            mutation = Mutation.CREATED,
            data = buildJsonObject {
                put("type", "ayah")
                put("key", 2)
                put("verseNumber", 255)
            },
            timestamp = null
        )
        val request = postRequestWithResponse(
            """
                {
                  "success": true,
                  "data": {
                    "lastMutationAt": 1234,
                    "mutations": [
                      {
                        "type": "CREATE",
                        "resource": "BOOKMARK",
                        "data": {
                          "type": "ayah",
                          "key": 2,
                          "verseNumber": 255
                        },
                        "timestamp": 1234
                      }
                    ]
                  }
                }
            """.trimIndent()
        )

        val response = request.postMutations(listOf(localMutation), 1000L, emptyMap())

        assertEquals(null, response.mutations.single().resourceId)
        assertFailsWith<IllegalStateException> {
            validatePushedMutationResponse(listOf(localMutation), response.mutations)
        }
    }

    @Test
    fun `postMutations splits large pushes into sequential batches chaining lastMutationAt`() = runTest {
        val backend = EchoingSyncBackend()
        val mutations = List(250) { index -> bookmarkCreateRequest(index) }

        val response = backend.request().postMutations(mutations, 1000L, emptyMap())

        assertEquals(listOf(100, 100, 50), backend.batchSizes)
        assertEquals(listOf(1000L, 1001L, 1002L), backend.lastMutationAtParameters)
        assertEquals(1003L, response.lastModificationDate)
        assertEquals(List(250) { index -> "remote-$index" }, response.mutations.map { it.resourceId })
        validatePushedMutationResponse(mutations, response.mutations)
    }

    @Test
    fun `postMutations stops at the first failed batch`() = runTest {
        val backend = EchoingSyncBackend(failingRequestIndex = 1)
        val mutations = List(250) { index -> bookmarkCreateRequest(index) }

        val exception = assertFailsWith<SyncNetworkException> {
            backend.request().postMutations(mutations, 1000L, emptyMap())
        }

        assertEquals(HttpStatusCode.Conflict, exception.status)
        assertEquals(listOf(100, 100), backend.batchSizes)
    }

    @Test
    fun `postMutations rejects a batch acknowledged with a different mutation count`() = runTest {
        val backend = EchoingSyncBackend(droppedAckRequestIndex = 1)
        val mutations = List(150) { index -> bookmarkCreateRequest(index) }

        assertFailsWith<IllegalStateException> {
            backend.request().postMutations(mutations, 1000L, emptyMap())
        }
    }

    private fun bookmarkCreateRequest(index: Int): SyncMutation =
        SyncMutation(
            resource = "BOOKMARK",
            resourceId = null,
            mutation = Mutation.CREATED,
            data = buildJsonObject {
                put("type", "ayah")
                put("key", index)
                put("verseNumber", 1)
            },
            timestamp = null
        )

    private fun collectionBookmarkCreateRequest(): SyncMutation =
        SyncMutation(
            resource = "COLLECTION_BOOKMARK",
            resourceId = null,
            mutation = Mutation.CREATED,
            data = buildJsonObject {
                put("collectionId", "collection-a")
                put("type", "ayah")
                put("key", 2)
                put("verseNumber", 255)
            },
            timestamp = null
        )

    private fun postRequestWithResponse(
        responseContent: String,
        responseStatus: HttpStatusCode = HttpStatusCode.OK
    ): PostMutationsRequest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = responseContent,
                    status = responseStatus,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
        ) {
            install(ContentNegotiation) {
                json(
                    Json {
                        explicitNulls = false
                        ignoreUnknownKeys = true
                    }
                )
            }
        }
        return PostMutationsRequest(client, "https://example.test")
    }

    private companion object {
        val errorResponseJson = Json {
            ignoreUnknownKeys = true
        }
    }
}

/** Acknowledges each pushed BOOKMARK create with `remote-<key>` and advances the head by one per request. */
private class EchoingSyncBackend(
    private val failingRequestIndex: Int? = null,
    private val droppedAckRequestIndex: Int? = null
) {
    val batchSizes = mutableListOf<Int>()
    val lastMutationAtParameters = mutableListOf<Long>()

    fun request(): PostMutationsRequest {
        val client = HttpClient(
            MockEngine { request ->
                val requestIndex = batchSizes.size
                val lastMutationAt = request.url.parameters["lastMutationAt"]!!.toLong()
                val mutations = Json.parseToJsonElement(request.body.toByteArray().decodeToString())
                    .jsonObject.getValue("mutations").jsonArray
                batchSizes += mutations.size
                lastMutationAtParameters += lastMutationAt
                if (requestIndex == failingRequestIndex) {
                    return@MockEngine respond(
                        content = """{"success":false,"error":{"code":"OutOfSyncError","message":"stale"}}""",
                        status = HttpStatusCode.Conflict,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
                val acks = mutations
                    .drop(if (requestIndex == droppedAckRequestIndex) 1 else 0)
                    .joinToString(",") { mutation ->
                        val data = mutation.jsonObject.getValue("data")
                        val key = data.jsonObject.getValue("key").jsonPrimitive.content
                        """{"type":"CREATE","resource":"BOOKMARK","resourceId":"remote-$key","data":$data}"""
                    }
                respond(
                    content = """{"success":true,"data":{"lastMutationAt":${lastMutationAt + 1},"mutations":[$acks]}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
        ) {
            install(ContentNegotiation) {
                json(
                    Json {
                        explicitNulls = false
                        ignoreUnknownKeys = true
                    }
                )
            }
        }
        return PostMutationsRequest(client, "https://example.test")
    }
}
