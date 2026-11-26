package com.quran.shared.syncv2.fake

import com.quran.shared.syncv2.Device
import com.quran.shared.syncv2.Remote
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fake server's own contract with the backend's `GET /v1/sync` continuation guard, checked over raw HTTP. */
class FakeSyncServerTest {
    private val server = FakeSyncServer()
    private val http = HttpClient.newHttpClient()
    private val remote = Remote(server)

    @AfterTest
    fun tearDown() = server.close()

    @Test
    fun `a page after the first without lastMutationAt is a 409 OutOfSyncError`() {
        repeat(3) { remote.createSession(1, it + 1, T0 + it) }

        val response = get(page = 2)

        assertEquals(409, response.statusCode(), response.body())
        val body = Json.parseToJsonElement(response.body()).jsonObject
        assertEquals("Client is out of sync", body["message"]!!.jsonPrimitive.content)
        val error = errorOf(response)
        assertEquals("OutOfSyncError", error["code"]!!.jsonPrimitive.content)
        assertEquals("Client is out of sync", error["message"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), error["details"])
    }

    @Test
    fun `a page after the first with a stale lastMutationAt is a 409 OutOfSyncError`() {
        repeat(3) { remote.createSession(1, it + 1, T0 + it) }
        val page1Head = headOf(get(page = 1))
        remote.createSession(2, 1, T0 + 10)

        assertEquals(409, get(page = 2, lastMutationAt = page1Head).statusCode())
    }

    /** The guard runs after the between-pages write lands, so page 1's head is stale by then. */
    @Test
    fun `a write between pages makes page 1's head stale for page 2`() {
        repeat(3) { remote.createSession(1, it + 1, T0 + it) }
        server.beforeGetPage = 2 to { remote.createSession(2, 1, T0 + 10) }
        val page1Head = headOf(get(page = 1))

        val response = get(page = 2, lastMutationAt = page1Head)

        assertEquals(409, response.statusCode(), response.body())
        assertEquals("OutOfSyncError", errorOf(response)["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a page after the first with page 1's head is served`() {
        repeat(3) { remote.createSession(1, it + 1, T0 + it) }
        val page1Head = headOf(get(page = 1))

        val page2 = get(page = 2, lastMutationAt = page1Head)

        assertEquals(200, page2.statusCode(), page2.body())
        assertEquals(2, dataOf(page2)["mutations"]!!.jsonArray.size)
    }

    @Test
    fun `page 1 never needs lastMutationAt`() {
        repeat(3) { remote.createSession(1, it + 1, T0 + it) }

        assertEquals(200, get(page = 1).statusCode())
        assertEquals(200, get(page = 1, lastMutationAt = 1).statusCode(), "a stale head on page 1 is ignored")
    }

    @Test
    fun `GET validates lastMutationAt as a non-negative integer`() {
        mapOf(
            "-1" to "\"lastMutationAt\" must be greater than or equal to 0",
            "1.5" to "\"lastMutationAt\" must be an integer",
            "soon" to "\"lastMutationAt\" must be a number"
        ).forEach { (value, message) ->
            val response = get(page = 1, lastMutationAt = value)
            assertEquals(422, response.statusCode(), value)
            val error = errorOf(response)
            assertEquals("ValidationError", error["code"]!!.jsonPrimitive.content, value)
            assertEquals(message, error["message"]!!.jsonPrimitive.content, value)
        }
    }

    /** Pages of 2: the virtual Favorites and 3 sessions make two pages. */
    private fun get(page: Int, lastMutationAt: Any? = null): HttpResponse<String> {
        val continuation = lastMutationAt?.let { "&lastMutationAt=$it" }.orEmpty()
        return http.send(
            HttpRequest.newBuilder(URI("${server.url}/v1/sync?mutationsSince=0&page=$page&limit=2$continuation"))
                .header("Authorization", "Bearer ${Device.ACCOUNT}").GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
    }

    private fun dataOf(response: HttpResponse<String>): JsonObject =
        Json.parseToJsonElement(response.body()).jsonObject["data"]!!.jsonObject

    /** The backend's `error` inside the gateway envelope. */
    private fun errorOf(response: HttpResponse<String>): JsonObject =
        Json.parseToJsonElement(response.body()).jsonObject["details"]!!.jsonObject["error"]!!.jsonObject

    private fun headOf(response: HttpResponse<String>): Long = dataOf(response)["lastMutationAt"]!!.jsonPrimitive.long

    private companion object {
        const val T0 = 1_760_000_000_000L
    }
}
