package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
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
    suspend fun postMutations(
        mutations: List<LocalModelMutation<PageBookmark>>,
        lastModificationDate: Long,
        authHeaders: Map<String, String>
    ): MutationsResponse {
        val requestBody = PostMutationsRequest(
            mutations = mutations.map { localMutation ->
                PostMutationRequest(
                    type = when (localMutation.mutation) {
                        Mutation.CREATED -> "CREATE"
                        Mutation.DELETED -> "DELETE"
                        Mutation.MODIFIED -> "UPDATE"
                    },
                    resource = "BOOKMARK",
                    data = PostMutationData(
                        type = "page",
                        key = localMutation.model.page,
                        mushaf = 1
                    )
                )
            }
        )

        val response: PostMutationsResponse = httpClient.post("$url/auth/v1/sync") {
            headers {
                authHeaders.forEach { (key, value) ->
                    append(key, value)
                }
                contentType(ContentType.Application.Json)
            }
            setBody(requestBody)
        }.body()

        return response.data.toMutationsResponse()
    }
}

private fun PostMutationsResponseData.toMutationsResponse(): MutationsResponse {
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
            else -> throw IllegalArgumentException("Unknown mutation type: ${postMutation.type}")
        }
        
        RemoteModelMutation(
            model = pageBookmark,
            remoteID = postMutation.resourceId,
            mutation = mutation
        )
    }
    
    return MutationsResponse(
        lastModificationDate = lastMutationAt,
        mutations = mutations
    )
}