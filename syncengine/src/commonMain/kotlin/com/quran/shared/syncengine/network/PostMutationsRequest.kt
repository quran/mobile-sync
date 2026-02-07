@file:OptIn(kotlin.time.ExperimentalTime::class)
package com.quran.shared.syncengine.network

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.Mutation
import com.quran.shared.syncengine.SyncMutation
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
        val data: JsonObject? = null
    )

    @Serializable
    private data class PostMutationsResponse(
        val success: Boolean,
        val data: PostMutationsResponseData
    )

    @Serializable
    private data class PostMutationsResponseData(
        val lastMutationAt: Long,
        val mutations: List<PostMutationResponse>,
        val page: Int? = null,
        val limit: Int? = null,
        val total: Int? = null,
        val hasMore: Boolean? = null
    )

    @Serializable
    private data class PostMutationResponse(
        val type: String,
        val resource: String,
        val data: JsonObject? = null,
        val resourceId: String,
        val timestamp: Long? = null
    )

    // endregion

    suspend fun postMutations(
        mutations: List<SyncMutation>,
        lastModificationDate: Long,
        authHeaders: Map<String, String>
    ): MutationsResponse {
        logger.i { "Starting POST mutations request to $url" }

        val requestBody = PostMutationsRequestData(
            mutations = mutations.map { localMutation ->
            PostMutationRequestData(
                type = localMutation.mutation.toRequestType(),
                resource = localMutation.resource,
                resourceId = localMutation.resourceId,
                data = localMutation.data
            )
        })

        val fullUrl = "$url/v1/sync"
        logger.i { "Starting POST mutations request to $fullUrl" }
        
        // Log request body summary
        logger.d { "Request body: mutations count=${requestBody.mutations.size}, lastMutationAt=$lastModificationDate" }
        if (requestBody.mutations.isNotEmpty()) {
            logger.v { "First mutation sample: ${requestBody.mutations.first()}" }
        }

        val httpResponse = httpClient.post(fullUrl) {
            headers {
                authHeaders.forEach { (key, value) ->
                    append(key, value)
                    if (key.equals("Authorization", ignoreCase = true)) {
                        logger.v { "Header: $key=Bearer ***" }
                    } else {
                        logger.v { "Header: $key=$value" }
                    }
                }
                contentType(ContentType.Application.Json)
            }
            parameter("lastMutationAt", lastModificationDate)
            setBody(requestBody)
        }

        logger.d { "HTTP response status: ${httpResponse.status}" }

        if (!httpResponse.status.isSuccess()) {
            httpResponse.processError(logger) {
                httpResponse.body<SyncErrorResponse>().message
            }
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
            val mutation = postMutation.type.asMutation(logger)
            SyncMutation(
                resource = postMutation.resource,
                resourceId = postMutation.resourceId,
                mutation = mutation,
                data = postMutation.data,
                timestamp = postMutation.timestamp
            )
        }

        val result = MutationsResponse(
            lastModificationDate = lastMutationAt,
            mutations = mutations
        )

        return result
    }
}

private fun Mutation.toRequestType(): String {
    return when (this) {
        Mutation.CREATED -> "CREATE"
        Mutation.DELETED -> "DELETE"
        Mutation.MODIFIED -> "UPDATE"
    }
}
