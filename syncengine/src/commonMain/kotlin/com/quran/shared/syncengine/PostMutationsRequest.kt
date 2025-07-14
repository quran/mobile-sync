package com.quran.shared.syncengine

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable
data class PostMutationsRequest(
    val mutations: List<PostMutationRequest>
)

@Serializable
data class PostMutationRequest(
    val type: String,
    val resource: String,
    val resourceId: String?,
    val data: PostMutationData
)

@Serializable
data class PostMutationData(
    val type: String,
    val key: Int,
    val mushaf: Int
)

@Serializable
data class PostMutationsResponse(
    val success: Boolean,
    val data: PostMutationsResponseData
)

@Serializable
data class PostMutationsResponseData(
    val lastMutationAt: Long,
    val mutations: List<PostMutationResponse>
)

@Serializable
data class PostMutationResponse(
    val type: String,
    val resource: String,
    val data: PostMutationData,
    val resourceId: String,
    val createdAt: Long
)

class PostMutationsRequestClient(
    private val httpClient: HttpClient,
    private val url: String
) {
    private val logger = Logger.withTag("PostMutationsRequestClient")
    suspend fun postMutations(
        mutations: List<LocalModelMutation<PageBookmark>>,
        lastModificationDate: Long,
        authHeaders: Map<String, String>
    ): MutationsResponse {
        logger.i { "Starting POST mutations request to $url" }
        logger.d { "Last modification date: $lastModificationDate" }

        val requestBody = PostMutationsRequest(
            mutations = mutations.map { localMutation ->
                val mutationType = when (localMutation.mutation) {
                    Mutation.CREATED -> "CREATE"
                    Mutation.DELETED -> "DELETE"
                    Mutation.MODIFIED -> "UPDATE"
                }

                PostMutationRequest(
                    type = mutationType,
                    resource = "BOOKMARK",
                    resourceId = localMutation.remoteID,
                    data = PostMutationData(
                        type = "page",
                        key = localMutation.model.page,
                        mushaf = 1
                    )
                )
            }
        )

        val httpResponse = httpClient.post("$url/auth/v1/sync") {
            headers {
                authHeaders.forEach { (key, value) ->
                    append(key, value)
                    logger.d { "Adding header: $key" }
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
            throw RuntimeException("HTTP request failed with status ${httpResponse.status}: $errorBody")
        }
        
        val response: PostMutationsResponse = httpResponse.body()
        
        if (!response.success) {
            logger.e { "Server returned success=false in response body" }
            logger.e { "Response data: lastMutationAt=${response.data.lastMutationAt}, mutations count=${response.data.mutations.size}" }
        }
        
        logger.i { "Received response: success=${response.success}" }

        logger.d { "Response data: lastMutationAt=${response.data.lastMutationAt}, mutations count=${response.data.mutations.size}" }
        
        response.data.mutations.forEachIndexed { index, mutation ->
            logger.d { "Response mutation $index: type=${mutation.type}, resourceId=${mutation.resourceId}, page=${mutation.data.key}, createdAt=${mutation.createdAt}" }
        }
        
        val result = response.data.toMutationsResponse()
        logger.i { "Converted to MutationsResponse: lastModificationDate=${result.lastModificationDate}, mutations count=${result.mutations.size}" }
        
        return result
    }
}

private fun PostMutationsResponseData.toMutationsResponse(): MutationsResponse {
    val logger = Logger.withTag("PostMutationsResponseConverter")
    
    logger.d { "Converting PostMutationsResponseData to MutationsResponse" }
    logger.d { "Input: lastMutationAt=$lastMutationAt, mutations count=${mutations.size}" }
    
    val mutations = mutations.map { postMutation ->
        val pageBookmark = PageBookmark(
            id = postMutation.resourceId,
            page = postMutation.data.key,
            lastModified = postMutation.createdAt
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
        
        logger.d { "Converting mutation: type=${postMutation.type} -> ${mutation}, page=${postMutation.data.key}, resourceId=${postMutation.resourceId}" }
        
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
    
    logger.d { "Conversion complete: lastModificationDate=${result.lastModificationDate}, mutations count=${result.mutations.size}" }
    
    return result
}