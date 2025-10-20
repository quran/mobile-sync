@file:OptIn(kotlin.time.ExperimentalTime::class)
package com.quran.shared.syncengine.network

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.PageBookmark
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlin.time.Instant

class PostMutationsRequest(
    private val httpClient: HttpClient,
    private val url: String
) {
    private val logger = Logger.withTag("PostMutationsRequestClient")
    
    // region: JSON Mapping.
    @Serializable
    private data class PostMutationsRequestData(
        val mutations: List<PostMutationRequestData>
    )

    @Serializable
    private data class PostMutationRequestData(
        val type: String,
        val resource: String,
        val resourceId: String?,
        val data: MutatedResourceData?
    )

    @Serializable
    private data class MutatedResourceData(
        val type: String,
        val key: Int,
        val mushaf: Int
    )

    @Serializable
    private data class PostMutationsResponse(
        val success: Boolean,
        val data: PostMutationsResponseData
    )

    @Serializable
    private data class PostMutationsResponseData(
        val lastMutationAt: Long,
        val mutations: List<PostMutationResponse>
    )

    @Serializable
    private data class PostMutationResponse(
        val type: String,
        val resource: String,
        val data: MutatedResourceData?,
        val resourceId: String,
        val createdAt: Long? = null
    )

    @Serializable
    private data class ErrorResponse(
        val message: String,
        val type: String,
        val success: Boolean
    )
    // endregion
    
    suspend fun postMutations(
        mutations: List<LocalModelMutation<PageBookmark>>,
        lastModificationDate: Long,
        authHeaders: Map<String, String>
    ): MutationsResponse {
        logger.i { "Starting POST mutations request to $url" }

        val requestBody = PostMutationsRequestData(
            mutations = mutations.map { localMutation ->
                val mutationType = when (localMutation.mutation) {
                    Mutation.CREATED -> "CREATE"
                    Mutation.DELETED -> "DELETE"
                    Mutation.MODIFIED -> "UPDATE"
                }

                PostMutationRequestData(
                    type = mutationType,
                    resource = "BOOKMARK",
                    resourceId = localMutation.remoteID,

                    data = if (localMutation.mutation == Mutation.DELETED) {null} else {
                        MutatedResourceData(
                            type = "page",
                            key = localMutation.model.page,
                            // TODO: Hardcoded to 1 for now. 
                            mushaf = 1
                        )
                    }
                )
            }
        )

        val httpResponse = httpClient.post("$url/auth/v1/sync") {
            headers {
                authHeaders.forEach { (key, value) ->
                    append(key, value)
                }
                contentType(ContentType.Application.Json)
            }
            parameter("lastMutationAt", lastModificationDate)
            setBody(requestBody)
        }
        
        logger.d { "HTTP response status: ${httpResponse.status}" }

        if (!httpResponse.status.isSuccess()) {
            val errorBody = httpResponse.bodyAsText()
            logger.e { "HTTP error response: status=${httpResponse.status}, body=$errorBody" }
            
            val errorMessage = try {
                val errorResponse: ErrorResponse = httpResponse.body()
                errorResponse.message
            } catch (e: Exception) {
                logger.w { "Failed to parse error response, using raw body: ${e.message}" }
                errorBody
            }
            
            // TODO: Replace with NetworkException or similar specific exception class
            throw RuntimeException("HTTP request failed with status ${httpResponse.status}: $errorMessage")
        }
        
        val response: PostMutationsResponse = httpResponse.body()
        
        logger.i { "Received response: success=${response.success}" }
        
        val result = response.data.toMutationsResponse()
        logger.i { "lastModificationDate=${result.lastModificationDate}, mutations count=${result.mutations.size}" }
        
        return result
    }
    
    private fun PostMutationsResponseData.toMutationsResponse(): MutationsResponse {
        val logger = Logger.withTag("PostMutationsResponseConverter")
        
        val mutations = mutations.map { postMutation ->
            val pageBookmark = PageBookmark(
                id = postMutation.resourceId,
                // TODO: Probably need to remodel Mutation types for DELETE events
                page = postMutation.data?.key ?: 0,
                // Not sent in deletions
                lastModified = postMutation.createdAt?.let { Instant.fromEpochSeconds(it) } ?: Instant.fromEpochSeconds(0)
            )
            
            val mutation = when (postMutation.type) {
                "CREATE" -> Mutation.CREATED
                "DELETE" -> Mutation.DELETED
                "UPDATE" -> Mutation.MODIFIED
                else -> {
                    logger.e { "Unknown mutation type: ${postMutation.type}" }
                    throw IllegalArgumentException("Unknown mutation type: ${postMutation.type}")
                }
            }

            RemoteModelMutation(
                model = pageBookmark,
                remoteID = postMutation.resourceId,
                mutation = mutation
            )
        }

        val result = MutationsResponse(
            lastModificationDate = lastMutationAt,
            mutations = mutations
        )
        
        return result
    }
}