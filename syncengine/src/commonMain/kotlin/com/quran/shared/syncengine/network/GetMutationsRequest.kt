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
import io.ktor.http.HttpStatusCode
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

    /**
     * Fetches every mutation after [lastModificationDate], following the backend's page/limit pagination.
     *
     * The backend pages with an offset over rows ordered by their last change, so a write from another
     * device mid-pagination can shift rows across page boundaries. Every write advances the returned
     * `lastMutationAt` head, so pages are only combined when they all report the same head; otherwise the
     * pagination restarts from the first page.
     */
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
        repeat(MAX_SNAPSHOT_ATTEMPTS) { snapshotAttempt ->
            val response = getConsistentMutations(lastModificationDate, authHeaders, resources, attempt)
            if (response != null) {
                return response
            }
            logger.w {
                "Remote sync head changed during pagination, restarting from the first page " +
                    "(snapshot attempt ${snapshotAttempt + 1}/$MAX_SNAPSHOT_ATTEMPTS)"
            }
        }
        throw IllegalStateException(
            "Remote sync head kept changing during pagination after $MAX_SNAPSHOT_ATTEMPTS attempts"
        )
    }

    /** Returns `null` when the remote head changed between pages. */
    private suspend fun getConsistentMutations(
        lastModificationDate: Long,
        authHeaders: Map<String, String>,
        resources: List<String>,
        attempt: Int
    ): MutationsResponse? {
        // The first page has nothing collected to preserve, so its failures go straight to the scheduler.
        val firstPage = getPage(lastModificationDate, authHeaders, resources, page = 1, attempt)
        val head = firstPage.lastMutationAt
        val maxPages = firstPage.total?.let { total -> (total + PAGE_LIMIT - 1) / PAGE_LIMIT + 1 }
        val mutations = firstPage.mutations.toMutableList()
        var currentPage = firstPage
        var page = 1
        while (currentPage.hasMore == true) {
            if (currentPage.mutations.isEmpty()) {
                throw IllegalStateException("Remote page $page is empty but reports more pages")
            }
            page += 1
            if (maxPages != null && page > maxPages) {
                throw IllegalStateException(
                    "Remote pagination exceeded $maxPages pages for total=${firstPage.total}"
                )
            }
            currentPage = getPageRetryingTransientFailures(
                lastModificationDate,
                authHeaders,
                resources,
                page,
                attempt
            )
            if (currentPage.lastMutationAt != head) {
                return null
            }
            mutations += currentPage.mutations
        }

        logger.i { "Fetched ${mutations.size} remote mutations across $page page(s), lastMutationAt=$head" }
        return firstPage.copy(mutations = mutations).toMutationsResponse()
    }

    private suspend fun getPageRetryingTransientFailures(
        lastModificationDate: Long,
        authHeaders: Map<String, String>,
        resources: List<String>,
        page: Int,
        attempt: Int
    ): ApiResponseData {
        var retryDelayMillis = PAGE_RETRY_BASE_DELAY_MILLIS
        repeat(MAX_PAGE_RETRIES) { retry ->
            try {
                return getPage(lastModificationDate, authHeaders, resources, page, attempt)
            } catch (exception: Exception) {
                if (!exception.isTransientPageFailure()) {
                    throw exception
                }
                logger.w {
                    "Transient failure fetching page $page (retry ${retry + 1}/$MAX_PAGE_RETRIES): " +
                        exception.message
                }
            }
            delay(retryDelayMillis)
            retryDelayMillis *= 2
        }
        return getPage(lastModificationDate, authHeaders, resources, page, attempt)
    }

    private suspend fun getPage(
        lastModificationDate: Long,
        authHeaders: Map<String, String>,
        resources: List<String>,
        page: Int,
        attempt: Int
    ): ApiResponseData {
        val logContext = SyncRequestLogContext.create(attempt)
        val fullUrl = "$url/v1/sync"
        logger.i { logContext.format("Starting GET mutations request to $fullUrl, page=$page") }
        logger.d {
            logContext.format(
                "Request params: mutationsSince=$lastModificationDate, resources=$resources, " +
                    "page=$page, limit=$PAGE_LIMIT"
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
                    "mutations count=${apiResponse.data.mutations.size}, page=${apiResponse.data.page}, " +
                    "total=${apiResponse.data.total}, hasMore=${apiResponse.data.hasMore}"
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
        /** The backend's maximum `limit`. */
        const val PAGE_LIMIT = 1000
        const val MAX_SNAPSHOT_ATTEMPTS = 3
        const val MAX_PAGE_RETRIES = 3
        const val PAGE_RETRY_BASE_DELAY_MILLIS = 500L
    }
}

private fun Exception.isTransientPageFailure(): Boolean =
    when (this) {
        is SyncNetworkException -> status.value >= 500 || status == HttpStatusCode.TooManyRequests
        is IOException -> true
        else -> false
    }
