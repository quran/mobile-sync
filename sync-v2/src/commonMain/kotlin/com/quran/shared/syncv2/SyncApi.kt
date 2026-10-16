package com.quran.shared.syncv2

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class RemoteChanges(val head: Long, val entries: List<RemoteEntry>)

data class PostResult(val head: Long, val mutations: List<PostedMutation>)

/**
 * A non-2xx answer, classified by the server's `error.code` and `error.details.mutationIndex`. It never carries the
 * server's message or body: they can quote what was sent (a note's body, a collection's name), and this is logged.
 */
class SyncHttpException(
    val status: Int,
    val code: String?,
    val mutationIndex: Int?
) : Exception(listOfNotNull("HTTP $status", code).joinToString(" "))

/** The sync head changed between GET pages (retryable). */
class HeadChangedException : IllegalStateException("Sync head changed during pagination")

/** `/v1/sync` over Ktor. Network failures surface as the engine's exceptions (IOException on JVM). */
class SyncApi(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val pageSize: Int = PAGE_LIMIT,
    private val authHeaders: suspend () -> Map<String, String>
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Every page since [since]. Pages are offset-based, so they describe one state only if the head stays the same:
     * when another device writes between pages, the fetch starts over, up to [GET_ATTEMPTS] times.
     */
    suspend fun get(since: Long): RemoteChanges {
        repeat(GET_ATTEMPTS - 1) {
            try {
                return fetch(since)
            } catch (changed: HeadChangedException) {
                // Another device wrote between pages: fetch again from the first page.
            }
        }
        return fetch(since)
    }

    private suspend fun fetch(since: Long): RemoteChanges {
        val entries = mutableListOf<RemoteEntry>()
        var page = 1
        var head: Long? = null
        while (true) {
            val auth = authHeaders()
            val response = httpClient.get("$baseUrl/v1/sync") {
                headers { auth.forEach { (key, value) -> append(key, value) } }
                parameter("mutationsSince", since)
                parameter("page", page)
                parameter("limit", pageSize)
            }
            val data = response.successData()
            val pageHead = data.long("lastMutationAt")
            if (head != null && head != pageHead) throw HeadChangedException()
            head = pageHead
            data["mutations"]!!.jsonArray.forEach { element ->
                val mutation = element.jsonObject
                entries += RemoteEntry(
                    resource = mutation.string("resource")!!,
                    resourceId = mutation.string("resourceId"),
                    type = mutation.string("type")!!,
                    data = mutation["data"] as? JsonObject ?: JsonObject(emptyMap()),
                    timestamp = mutation.long("timestamp")
                )
            }
            if ((data["hasMore"] as? JsonPrimitive)?.contentOrNull != "true") break
            page += 1
        }
        return RemoteChanges(head, entries)
    }

    suspend fun post(head: Long, mutations: List<OutgoingMutation>): PostResult {
        val body = buildJsonObject {
            put("mutations", buildJsonArray {
                mutations.forEach { mutation ->
                    add(buildJsonObject {
                        put("type", mutation.type)
                        put("resource", mutation.resource)
                        mutation.resourceId?.let { put("resourceId", it) }
                        mutation.data?.let { put("data", it) }
                    })
                }
            })
        }
        val auth = authHeaders()
        val response = httpClient.post("$baseUrl/v1/sync") {
            headers { auth.forEach { (key, value) -> append(key, value) } }
            parameter("lastMutationAt", head)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val data = response.successData()
        val posted = (data["mutations"] as? JsonArray).orEmpty().map { element ->
            val mutation = element.jsonObject
            PostedMutation(
                type = mutation.string("type")!!,
                resource = mutation.string("resource")!!,
                resourceId = mutation.string("resourceId"),
                data = mutation["data"],
                timestamp = (mutation["timestamp"] as? JsonPrimitive)?.longOrNull
            )
        }
        return PostResult(data.long("lastMutationAt"), posted)
    }

    private suspend fun HttpResponse.successData(): JsonObject {
        val text = bodyAsText()
        if (!status.isSuccess()) throw parseSyncError(status.value, text)
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            // SerializationException is one, and its message quotes the response (note bodies, collection names) into
            // whatever logs the failure: neither the message nor the cause may go along.
            throw IllegalStateException("The sync server's response is not valid JSON")
        }
        return root.jsonObject["data"]!!.jsonObject
    }

    private fun JsonObject.long(key: String): Long = this[key]!!.jsonPrimitive.longOrNull!!

    private companion object {
        const val PAGE_LIMIT = 1000
        const val GET_ATTEMPTS = 3
    }
}

/**
 * Reads `error.code` and `error.details.mutationIndex` from either error shape:
 * - the backend's own: `{success:false, error:{code, message, details}}`;
 * - the API gateway's wrapper in production: `{message, type, success, requestId, details:{success, error:{...}}}`.
 * Gateway-only errors (401, 429, 5xx from the gateway itself) may carry no `error` at all.
 */
internal fun parseSyncError(status: Int, text: String): SyncHttpException {
    val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    val error = root?.obj("error") ?: root?.obj("details")?.obj("error")
    val index = (error?.obj("details")?.get("mutationIndex") as? JsonPrimitive)?.intOrNull
    return SyncHttpException(status, error?.string("code")?.takeIf { it.isNotBlank() }, index)
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
