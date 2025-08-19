package com.quran.shared.syncengine.network

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.PageBookmark
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant

class GetMutationsRequest(
    private val httpClient: HttpClient,
    private val url: String
) {
    private val logger = Logger.withTag("GetMutationsRequest")

    // region: JSON Mapping.
    @Serializable
    private data class ApiResponse(
        val success: Boolean,
        val data: ApiResponseData
    )

    @Serializable
    private data class ApiResponseData(
        val lastMutationAt: Long,
        val mutations: List<ApiMutation>
    )

    @Serializable
    private data class ApiMutation(
        val resource: String,
        val resourceId: String,
        val type: String,
        val data: ApiMutationData,
        val timestamp: Long
    )

    @Serializable
    private data class ApiMutationData(
        val bookmarkType: String? = null,
        val bookmarkGroup: String? = null,
        val key: Int? = null,
        val verseNumber: Int? = null
    )

    @Serializable
    private data class ErrorResponse(
        val message: String,
        val type: String,
        val success: Boolean
    )
    // endregion
    
    suspend fun getMutations(
        lastModificationDate: Long,
        authHeaders: Map<String, String>
    ): MutationsResponse {
        val httpResponse = httpClient.get("$url/auth/v1/sync") {
            headers {
                authHeaders.forEach { (key, value) ->
                    append(key, value)
                }
                contentType(ContentType.Application.Json)
            }
            parameter("mutationsSince", lastModificationDate)
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
        
        val apiResponse: ApiResponse = httpResponse.body()
        
        if (!apiResponse.success) {
            logger.e { "Server returned success=false in response body" }
            logger.e { "Response data: lastMutationAt=${apiResponse.data.lastMutationAt}, mutations count=${apiResponse.data.mutations.size}" }
            throw RuntimeException("Server returned success=false in response body")
        }
        
        logger.i { "Received response: success=${apiResponse.success}" }
        logger.d { "Response data: lastMutationAt=${apiResponse.data.lastMutationAt}, mutations count=${apiResponse.data.mutations.size}" }

        return apiResponse.data.toMutationsResponse()
    }
    
    private fun ApiResponseData.toMutationsResponse(): MutationsResponse {
        val logger = Logger.withTag("GetMutationsResponseConverter")
        
        val mutations = mutations.map { apiMutation ->
            val pageBookmark = PageBookmark(
                id = apiMutation.resourceId,
                page = apiMutation.data.key ?: 0,
                lastModified = Instant.fromEpochSeconds(apiMutation.timestamp)
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
        
        return result
    }
}