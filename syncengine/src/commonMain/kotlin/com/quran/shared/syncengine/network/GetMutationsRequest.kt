@file:OptIn(kotlin.time.ExperimentalTime::class)
package com.quran.shared.syncengine.network

import co.touchlab.kermit.Logger
import com.quran.shared.syncengine.SyncMutation
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
        val mutations: List<ApiMutation>,
        val page: Int? = null,
        val limit: Int? = null,
        val total: Int? = null,
        val hasMore: Boolean? = null
    )

    @Serializable
    private data class ApiMutation(
        val resource: String,
        val resourceId: String? = null,
        val type: String,
        val data: JsonObject? = null,
        val timestamp: Long
    )

    // endregion
    
    suspend fun getMutations(
        lastModificationDate: Long,
        authHeaders: Map<String, String>,
        resources: List<String> = emptyList()
    ): MutationsResponse =
        getMutations(lastModificationDate, authHeaders, resources, attempt = 0)

    internal suspend fun getMutations(
        lastModificationDate: Long,
        authHeaders: Map<String, String>,
        resources: List<String>,
        attempt: Int
    ): MutationsResponse {
        val logContext = SyncRequestLogContext.create(attempt)
        val fullUrl = "$url/v1/sync"
        logger.i { logContext.format("Starting GET mutations request to $fullUrl") }
        logger.d {
            logContext.format(
                "Request params: mutationsSince=$lastModificationDate, resources=$resources"
            )
        }

        val httpResponse = httpClient.get(fullUrl) {
            headers {
                authHeaders.forEach { (key, value) ->
                    append(key, value)
                }
                contentType(ContentType.Application.Json)
            }
            parameter("mutationsSince", lastModificationDate)
            if (resources.isNotEmpty()) {
                parameter("resources", resources.joinToString(","))
            }
        }
        
        logger.d { logContext.format("HTTP response status: ${httpResponse.status}") }
        if (!httpResponse.status.isSuccess()) {
            httpResponse.processError(logger, logContext)
        }
        
        val apiResponse: ApiResponse = httpResponse.body()
        if (!apiResponse.success) {
            logger.e { logContext.format("Server returned success=false in response body") }
            logger.e {
                logContext.format(
                    "Response data: lastMutationAt=${apiResponse.data.lastMutationAt}, " +
                        "mutations count=${apiResponse.data.mutations.size}"
                )
            }
            throw RuntimeException("Server returned success=false in response body")
        }
        
        logger.i { logContext.format("Received response: success=${apiResponse.success}") }
        logger.d {
            logContext.format(
                "Response data: lastMutationAt=${apiResponse.data.lastMutationAt}, " +
                    "mutations count=${apiResponse.data.mutations.size}"
            )
        }

        return apiResponse.data.toMutationsResponse()
    }
    
    private fun ApiResponseData.toMutationsResponse(): MutationsResponse {
        val mutations = mutations.map { apiMutation ->
            val mutation = apiMutation.type.asMutation(logger)
            SyncMutation(
                resource = apiMutation.resource,
                resourceId = apiMutation.resourceId,
                mutation = mutation,
                data = apiMutation.data,
                timestamp = apiMutation.timestamp
            )
        }

        val result = MutationsResponse(
            lastModificationDate = lastMutationAt,
            mutations = mutations
        )
        
        return result
    }
}
