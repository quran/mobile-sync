@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine.network

import com.quran.shared.mutations.Mutation
import com.quran.shared.syncengine.SyncMutation
import com.quran.shared.syncengine.validatePushedMutationResponse
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
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
                json(Json { explicitNulls = false })
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
    fun `postMutations decodes collection bookmark create ACK with missing resource id`() = runTest {
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

    private fun postRequestWithResponse(responseContent: String): PostMutationsRequest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = responseContent,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
        ) {
            install(ContentNegotiation) {
                json(Json { explicitNulls = false })
            }
        }
        return PostMutationsRequest(client, "https://example.test")
    }
}
