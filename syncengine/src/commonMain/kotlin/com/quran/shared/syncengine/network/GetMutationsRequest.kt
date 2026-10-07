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
import kotlinx.coroutines.delay
import kotlinx.io.IOException
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
        val firstPage = getPage(lastModificationDate, authHeaders, resources, attempt, page = 1)
        val pageCount = firstPage.total?.let { (it + PAGE_LIMIT - 1) / PAGE_LIMIT } ?: 1
        val mutations = firstPage.mutations + (2..pageCount).flatMap { page ->
            val response = retryingTransientFailures {
                getPage(lastModificationDate, authHeaders, resources, attempt, page)
            }
            check(response.lastMutationAt == firstPage.lastMutationAt) { "Sync head changed during pagination" }
            response.mutations
        }
        return firstPage.copy(mutations = mutations).toMutationsResponse()
    }

    private suspend fun <T> retryingTransientFailures(block: suspend () -> T): T {
        repeat(MAX_PAGE_RETRIES) { retry ->
            try {
                return block()
            } catch (exception: Exception) {
                if (!exception.isTransient()) throw exception
            }
            delay(PAGE_RETRY_DELAY_MILLIS shl retry)
        }
        return block()
    }

    private suspend fun getPage(
        lastModificationDate: Long,
        authHeaders: Map<String, String>,
        resources: List<String>,
        attempt: Int,
        page: Int
    ): ApiResponseData {
        val logContext = SyncRequestLogContext.create(attempt)
        val fullUrl = "$url/v1/sync"
        logger.i { logContext.format("Starting GET mutations request to $fullUrl") }
        logger.d {
            logContext.format(
                "Request params: mutationsSince=$lastModificationDate, resources=$resources, page=$page"
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
            parameter("page", page)
            parameter("limit", PAGE_LIMIT)
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

        return apiResponse.data
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

    internal companion object {
        const val PAGE_LIMIT = 1000
        private const val MAX_PAGE_RETRIES = 3
        private const val PAGE_RETRY_DELAY_MILLIS = 500L
    }
}

private fun Exception.isTransient(): Boolean =
    this is IOException || this is SyncNetworkException && status.value >= 500
