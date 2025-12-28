@file:OptIn(kotlin.time.ExperimentalTime::class)
package com.quran.shared.syncengine.network

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncBookmark
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
        val key: Int,
        val mushaf: Int,
        val verseNumber: Int? = null,
        val type: String
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
        val timestamp: Long? = null
    )

    @Serializable
    private data class ErrorResponse(
        val message: String,
        val type: String,
        val success: Boolean
    )
    // endregion

    suspend fun postMutations(
        mutations: List<LocalModelMutation<SyncBookmark>>,
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

                    data = if (localMutation.mutation == Mutation.DELETED) {
                        null
                    } else {
                        localMutation.model.toResourceData()
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
            httpResponse.processError(logger) {
                httpResponse.body<ErrorResponse>().message
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

        val mutations = mutations.mapNotNull { postMutation ->
            val mutation = postMutation.type.asMutation(logger)
            val syncBookmark = postMutation.toSyncBookmark(logger)
            if (syncBookmark != null) {
                RemoteModelMutation(
                    model = syncBookmark,
                    remoteID = postMutation.resourceId,
                    mutation = mutation
                )
            } else {
                null
            }
        }

        val result = MutationsResponse(
            lastModificationDate = lastMutationAt,
            mutations = mutations
        )

        return result
    }

    private fun SyncBookmark.toResourceData(): MutatedResourceData {
        return when (this) {
            is SyncBookmark.PageBookmark ->
                MutatedResourceData(
                    type = "page",
                    key = page,
                    // TODO: Hardcoded to 1 for now.
                    mushaf = 1
                )
            is SyncBookmark.AyahBookmark ->
                MutatedResourceData(
                    type = "ayah",
                    key = sura,
                    verseNumber = ayah,
                    // TODO: Hardcoded to 1 for now.
                    mushaf = 1
                )
        }
    }

    private fun PostMutationResponse.toSyncBookmark(logger: Logger): SyncBookmark? {
        val lastModified = timestamp?.let { Instant.fromEpochSeconds(it) } ?:
           Instant.fromEpochSeconds(0)
        return when (resource.lowercase()) {
            "bookmark" ->
                when (val normalizedType = data?.type?.lowercase()) {
                    "page" -> {
                        SyncBookmark.PageBookmark(
                            id = resourceId,
                            page = data.key,
                            lastModified = lastModified
                        )
                    }

                    "ayah" -> {
                        val sura = data.key
                        val ayah = data.verseNumber
                        if (ayah != null) {
                            SyncBookmark.AyahBookmark(
                                id = resourceId,
                                sura = sura,
                                ayah = ayah,
                                lastModified = lastModified
                            )
                        } else {
                            null
                        }
                    }

                    else -> {
                        logger.w { "Unknown bookmark type=$normalizedType in mutation response: resourceId=$resourceId" }
                        null
                    }
                }
            else -> null
        }
    }
}
