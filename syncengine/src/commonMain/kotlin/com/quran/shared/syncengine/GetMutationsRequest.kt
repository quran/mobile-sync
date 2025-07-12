package com.quran.shared.syncengine

import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
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
    suspend fun getMutations(
        lastModificationDate: Long,
        authHeaders: Map<String, String>
    ): MutationsResponse {
        val apiResponse: ApiResponse = httpClient.get("$url/auth/v1/sync") {
            headers {
                authHeaders.forEach { (key, value) ->
                    append(key, value)
                }
            }
            parameter("mutationsSince", lastModificationDate)
        }.body()
        
        return apiResponse.data.toMutationsResponse()
    }
}

private fun ApiResponseData.toMutationsResponse(): MutationsResponse {
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
            else -> throw IllegalArgumentException("Unknown mutation type: ${apiMutation.type}")
        }
        
        RemoteModelMutation(
            model = pageBookmark,
            remoteID = apiMutation.resourceId,
            mutation = mutation
        )
    }
    
    return MutationsResponse(
        lastModificationDate = lastMutationAt,
        mutations = mutations
    )
}