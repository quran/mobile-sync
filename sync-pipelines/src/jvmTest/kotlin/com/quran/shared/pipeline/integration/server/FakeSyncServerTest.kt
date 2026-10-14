package com.quran.shared.pipeline.integration.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The fake server's own contract with the backend, checked over raw HTTP. */
class FakeSyncServerTest {
    private val server = FakeSyncServer()
    private val http = HttpClient.newHttpClient()
    private val token = server.issueToken(ALICE, "raw")

    @AfterTest
    fun tearDown() = server.close()

    @Test
    fun `htmlStrip drops tags, keeps their text, escapes and trims like sanitize-html`() {
        assertEquals("bold text", htmlStrip("<b>bold</b> text"))
        // An unescaped "<Tafsir>" typed on the web is a tag; the apps escape text before sending it.
        assertEquals("Q&amp;A", htmlStrip("Q&A <Tafsir>"))
        assertEquals("Q&amp;A &lt;Tafsir&gt;", htmlStrip("Q&amp;A &lt;Tafsir&gt;"))
        assertEquals("x &lt; y and \"q\"", htmlStrip("  x < y and \"q\"\n"))
        assertEquals("a", htmlStrip("a<script>alert(1)</script>"))
        assertEquals("x", htmlStrip("x<y"))
        assertNull(htmlStrip("<br/>  "))
    }

    @Test
    fun `body validation runs before the head check`() {
        val stale = post(lastMutationAt = 1, mutation("NOTE", "UPDATE", resourceId = "n1", data = """{"body":"Edited text","ranges":["2:255-2:255"]}"""))

        assertEquals(422, stale.statusCode())
        val error = errorOf(stale)
        assertEquals("ValidationError", error.str("code"))
        assertEquals("\"ranges\" is not allowed", error.str("message"))
    }

    @Test
    fun `errors use the API gateway envelope and add mutationIndex only when it is on`() {
        val head = server.head(ALICE)
        val bad = mutation("COLLECTION", "CREATE", data = """{"name":"<br>"}""")
        val good = mutation("COLLECTION", "CREATE", data = """{"name":"Study"}""")

        val production = Json.parseToJsonElement(post(head, good, bad).body()).jsonObject
        assertEquals(setOf("message", "type", "success", "requestId", "details"), production.keys)
        assertEquals("unprocessable_entity", production.str("type"))
        assertEquals(JsonObject(emptyMap()), production["details"]!!.jsonObject["error"]!!.jsonObject["details"])

        server.faults.mutationIndex = true
        val be1 = errorOf(post(head, good, bad))
        assertEquals(1L, be1["details"]!!.jsonObject["mutationIndex"]!!.jsonPrimitive.long)
    }

    @Test
    fun `a note range naming a missing verse is a 500 that reports its mutation when mutationIndex is on`() {
        server.faults.mutationIndex = true

        val response = post(server.head(ALICE), mutation("NOTE", "CREATE", data = NOTE), mutation("NOTE", "CREATE", data = MISSING_VERSE))

        assertEquals(500, response.statusCode())
        val error = errorOf(response)
        assertEquals("ServerError", error.str("code"))
        assertEquals(1L, error["details"]!!.jsonObject["mutationIndex"]!!.jsonPrimitive.long)
        assertEquals(0, server.state(ALICE).notes.size)
    }

    @Test
    fun `typed errors always carry details and the bare backend envelope can replace the gateway one`() {
        server.errorEnvelope = ErrorEnvelope.BACKEND

        val response = post(server.head(ALICE), mutation("NOTE", "DELETE", resourceId = "missing"))

        assertEquals(404, response.statusCode())
        val body = Json.parseToJsonElement(response.body()).jsonObject
        assertEquals(setOf("success", "error"), body.keys)
        assertEquals(JsonObject(emptyMap()), body["error"]!!.jsonObject["details"])
    }

    @Test
    fun `metadataOnly returns only the head`() {
        server.web(ALICE).createNote("A valid note body", "1:1-1:7")

        val response = http.send(
            HttpRequest.newBuilder(URI("${server.url}/v1/sync?mutationsSince=0&metadataOnly=true"))
                .header("x-auth-token", token).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )

        val data = Json.parseToJsonElement(response.body()).jsonObject["data"]!!.jsonObject
        assertEquals(setOf("lastMutationAt"), data.keys)
        assertEquals(server.head(ALICE), data["lastMutationAt"]!!.jsonPrimitive.long)
    }

    @Test
    fun `a bookmark body with only mushafId falls back to the default group like the backend`() {
        post(server.head(ALICE), mutation("BOOKMARK", "CREATE", data = """{"type":"page","key":300,"mushafId":7}"""))
        post(server.head(ALICE), mutation("BOOKMARK", "CREATE", data = """{"type":"page","key":301,"mushaf":7}"""))

        val groups = server.state(ALICE).bookmarks.associate { it.key to it.group }
        assertEquals(mapOf(300 to "pages_604", 301 to "pages_548"), groups)
    }

    @Test
    fun `an exception that models no backend behavior is recorded as a fake bug`() {
        server.faults.beforePost { error("broken test callback") }

        val response = post(server.head(ALICE), mutation("NOTE", "CREATE", data = NOTE))

        assertEquals(500, response.statusCode())
        assertEquals(1, server.internalErrors.size)
    }

    @Test
    fun `a duplicate collection name is a 409 UniqueViolationError marked DUPLICATE and rolls back the batch`() {
        server.web(ALICE).createCollection("Study")
        val head = server.head(ALICE)

        val response = post(head, mutation("NOTE", "CREATE", data = NOTE), mutation("COLLECTION", "CREATE", data = """{"name":" study "}"""))

        assertEquals(409, response.statusCode())
        val error = errorOf(response)
        assertEquals("UniqueViolationError", error.str("code"))
        assertEquals("DUPLICATE", error["details"]!!.jsonObject.str("name"))
        assertEquals(0, server.state(ALICE).notes.size)
        assertEquals(head, server.head(ALICE))
    }

    @Test
    fun `POST echoes the validated request while GET returns the stored row`() {
        val response = post(server.head(ALICE), mutation("NOTE", "CREATE", data = """{"body":"  <i>Patience</i> & light  ","ranges":["2:153-2:153"]}"""))
        val echo = Json.parseToJsonElement(response.body()).jsonObject["data"]!!.jsonObject["mutations"]!!.jsonArray.single().jsonObject

        assertEquals("Patience &amp; light", echo["data"]!!.jsonObject.str("body"))
        assertEquals("false", echo["data"]!!.jsonObject["saveToQR"].toString())
        val entry = get(since = 0).single { it.str("resource") == "NOTE" }
        assertEquals("Patience &amp; light", entry["data"]!!.jsonObject.str("body"))
        assertEquals(echo.str("resourceId"), entry.str("resourceId"))
    }

    @Test
    fun `the virtual Favorites entry takes a slot on page 1 and shifts later pages`() {
        repeat(5) { server.web(ALICE).addReadingSession(1, it + 1) }

        val page1 = get(since = 0, page = 1, limit = 3)
        val page2 = get(since = 0, page = 2, limit = 3)

        assertEquals("__default__", page1.first().str("resourceId"))
        assertEquals(3, page1.size)
        assertEquals(3, page2.size)
        assertEquals(5, (page1 + page2).count { it.str("resource") == "READING_SESSION" })
        assertFalse((page1 + page2).groupBy { it.str("resourceId") }.any { it.value.size > 1 })
    }

    @Test
    fun `removing a verse from Favorites deletes a bookmark in no other collection`() {
        server.web(ALICE).addBookmark(2, 255)
        val bookmarkId = server.state(ALICE).bookmarkAt(2, 255)!!.id

        val response = post(server.head(ALICE), mutation("COLLECTION_BOOKMARK", "DELETE", data = """{"collectionId":"__default__","bookmarkId":"$bookmarkId"}"""))

        assertEquals(200, response.statusCode())
        val echo = Json.parseToJsonElement(response.body()).jsonObject["data"]!!.jsonObject["mutations"]!!.jsonArray.single().jsonObject
        assertNull(echo["resourceId"])
        assertNull(server.state(ALICE).bookmarkAt(2, 255))
        val deletes = get(since = 0).filter { it.str("type") == "DELETE" }.map { it.str("resource") }
        assertEquals(listOf("BOOKMARK", "COLLECTION_BOOKMARK"), deletes)
    }

    private fun mutation(resource: String, type: String, resourceId: String? = null, data: String? = null) = buildString {
        append("""{"resource":"$resource","type":"$type"""")
        resourceId?.let { append(""","resourceId":"$it"""") }
        data?.let { append(""","data":$it""") }
        append("}")
    }

    private fun post(lastMutationAt: Long, vararg mutations: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("${server.url}/v1/sync?lastMutationAt=$lastMutationAt"))
            .header("x-auth-token", token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"mutations":[${mutations.joinToString(",")}]}"""))
            .build(),
        HttpResponse.BodyHandlers.ofString()
    )

    private fun get(since: Long, page: Int = 1, limit: Int = 1000): List<JsonObject> {
        val response = http.send(
            HttpRequest.newBuilder(URI("${server.url}/v1/sync?mutationsSince=$since&page=$page&limit=$limit"))
                .header("x-auth-token", token).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(200, response.statusCode(), response.body())
        val data = Json.parseToJsonElement(response.body()).jsonObject["data"]!!.jsonObject
        return (data["mutations"] as JsonArray).map { it.jsonObject }
    }

    /** The backend's `error` inside the gateway envelope. */
    private fun errorOf(response: HttpResponse<String>): JsonObject =
        Json.parseToJsonElement(response.body()).jsonObject["details"]!!.jsonObject["error"]!!.jsonObject

    private fun JsonObject.str(key: String): String? = text(key)

    private companion object {
        const val ALICE = "alice"
        const val NOTE = """{"body":"A valid note body","ranges":["1:1-1:7"]}"""
        const val MISSING_VERSE = """{"body":"A note on 2:300","ranges":["2:300-2:300"]}"""
    }
}
