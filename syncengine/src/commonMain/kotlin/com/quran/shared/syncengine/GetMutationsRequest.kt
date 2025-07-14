package com.quran.shared.syncengine

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse(
    val success: Boolean,
    val data: ApiResponseData
)

@Serializable
data class ApiResponseData(
    val lastMutationAt: Long,
    val mutations: List<ApiMutation>
)

@Serializable
data class ApiMutation(
    val resource: String,
    val resourceId: String,
    val type: String,
    val data: ApiMutationData,
    val timestamp: Long
)

@Serializable
data class ApiMutationData(
    val bookmarkType: String? = null,
    val bookmarkGroup: String? = null,
    val key: Int? = null,
    val verseNumber: Int? = null
)

class GetMutationsRequest(
    private val httpClient: HttpClient,
    private val url: String
) {
    private val logger = Logger.withTag("GetMutationsRequest")
    suspend fun getMutations(
        lastModificationDate: Long,
        authHeaders: Map<String, String>
    ): MutationsResponse {
        logger.i { "Starting GET mutations request to $url" }
        logger.d { "Last modification date: $lastModificationDate" }
        logger.d { "Auth headers count: ${authHeaders.size}" }
        
        val httpResponse = httpClient.get("$url/auth/v1/sync") {
            headers {
                authHeaders.forEach { (key, value) ->
                    append(key, value)
                    logger.d { "Adding header: $key" }
                }
            }
            parameter("mutationsSince", lastModificationDate)
        }
        
        logger.d { "HTTP response status: ${httpResponse.status}" }
        
        if (!httpResponse.status.isSuccess()) {
            val errorBody = httpResponse.bodyAsText()
            logger.e { "HTTP error response: status=${httpResponse.status}, body=$errorBody" }
            throw RuntimeException("HTTP request failed with status ${httpResponse.status}: $errorBody")
        }
        
        val apiResponse: ApiResponse = httpResponse.body()
        
        if (!apiResponse.success) {
            logger.e { "Server returned success=false in response body" }
            logger.e { "Response data: lastMutationAt=${apiResponse.data.lastMutationAt}, mutations count=${apiResponse.data.mutations.size}" }
        }
        
        logger.i { "Received response: success=${apiResponse.success}" }
        logger.d { "Response data: lastMutationAt=${apiResponse.data.lastMutationAt}, mutations count=${apiResponse.data.mutations.size}" }
        
        apiResponse.data.mutations.forEachIndexed { index, mutation ->
            logger.d { "Response mutation $index: type=${mutation.type}, resourceId=${mutation.resourceId}, page=${mutation.data.key}, timestamp=${mutation.timestamp}" }
        }
        
        val result = apiResponse.data.toMutationsResponse()
        logger.i { "Converted to MutationsResponse: lastModificationDate=${result.lastModificationDate}, mutations count=${result.mutations.size}" }
        
        return result
    }
}

private fun ApiResponseData.toMutationsResponse(): MutationsResponse {
    val logger = Logger.withTag("GetMutationsResponseConverter")
    
    logger.d { "Converting ApiResponseData to MutationsResponse" }
    logger.d { "Input: lastMutationAt=$lastMutationAt, mutations count=${mutations.size}" }
    
    val mutations = mutations.map { apiMutation ->
        val pageBookmark = PageBookmark(
            id = apiMutation.resourceId,
            page = apiMutation.data.key ?: 0,
            lastModified = apiMutation.timestamp
        )
        
        val mutation = when (apiMutation.type) {
            "CREATE" -> Mutation.CREATED
            "DELETE" -> Mutation.DELETED
            "UPDATE" -> Mutation.MODIFIED
            else -> {
                logger.e { "Unknown mutation type: ${apiMutation.type}" }
                throw IllegalArgumentException("Unknown mutation type: ${apiMutation.type}")
            }
        }
        
        logger.d { "Converting mutation: type=${apiMutation.type} -> ${mutation}, page=${apiMutation.data.key}, resourceId=${apiMutation.resourceId}" }
        
        RemoteModelMutation(
            model = pageBookmark,
            remoteID = apiMutation.resourceId,
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