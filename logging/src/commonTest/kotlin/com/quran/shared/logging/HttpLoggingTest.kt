package com.quran.shared.logging

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpLoggingTest {
    private data class Entry(val severity: MobileSyncLogSeverity, val tag: String, val message: String)

    private val entries = mutableListOf<Entry>()

    private fun configure(minSeverity: MobileSyncLogSeverity) {
        MobileSyncLogging.configure(
            sink = { severity, tag, message -> entries += Entry(severity, tag, message) },
            minSeverity = minSeverity
        )
    }

    @AfterTest
    fun tearDown() {
        MobileSyncLogging.configure(sink = { _, _, _ -> }, minSeverity = MobileSyncLogSeverity.VERBOSE)
    }

    private fun client(
        responseBody: String = """{"ok":true}""",
        redact: (String) -> String = { it }
    ) = HttpClient(
        MockEngine {
            respond(
                content = responseBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
    ) {
        installHttpLogging(tag = "TestHttp", redact = redact)
    }

    @Test
    fun `info logs one line per request and response`() = runTest {
        configure(MobileSyncLogSeverity.INFO)

        client().get("https://example.com/v1/sync?page=2").bodyAsText()

        assertEquals(
            listOf(
                MobileSyncLogSeverity.INFO to "--> GET https://example.com/v1/sync?page=2",
                MobileSyncLogSeverity.INFO to "<-- 200 OK https://example.com/v1/sync?page=2",
            ),
            entries.map { it.severity to it.message.substringBefore(" (") }
        )
        assertTrue(entries.all { it.tag == "TestHttp" })
    }

    @Test
    fun `debug adds headers and bodies after the summary`() = runTest {
        configure(MobileSyncLogSeverity.DEBUG)

        client(responseBody = """{"mutations":[]}""").post("https://example.com/v1/sync") {
            header(HttpHeaders.Authorization, "Bearer secret-token")
            contentType(ContentType.Application.Json)
            setBody("""{"mutations":[1]}""")
        }.bodyAsText()

        val severities = entries.map { it.severity }
        assertEquals(
            listOf(
                MobileSyncLogSeverity.INFO,
                MobileSyncLogSeverity.DEBUG,
                MobileSyncLogSeverity.INFO,
                MobileSyncLogSeverity.DEBUG
            ),
            severities
        )
        val (requestSummary, requestDetails, responseSummary, responseDetails) = entries.map { it.message }
        assertEquals("--> POST https://example.com/v1/sync", requestSummary)
        assertTrue(requestDetails.startsWith("$requestSummary\n"))
        assertTrue(requestDetails.contains("""{"mutations":[1]}"""))
        assertFalse(requestDetails.contains("secret-token"))
        assertTrue(responseDetails.startsWith("$responseSummary\n"))
        assertTrue(responseDetails.contains("""{"mutations":[]}"""))
    }

    @Test
    fun `debug logs long bodies whole`() = runTest {
        configure(MobileSyncLogSeverity.DEBUG)
        val longBody = "a".repeat(20_000)

        client(responseBody = longBody).get("https://example.com/v1/sync").bodyAsText()

        assertTrue(entries.last().message.contains(longBody))
    }

    @Test
    fun `debug redacts request and response bodies`() = runTest {
        configure(MobileSyncLogSeverity.DEBUG)
        val secret = Regex("secret-[a-z]+")

        client(
            responseBody = """{"access_token":"secret-access"}""",
            redact = { it.replace(secret, "***") }
        ).post("https://example.com/token") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("grant_type=authorization_code&code=secret-code")
        }.bodyAsText()

        val (_, requestDetails, _, responseDetails) = entries.map { it.message }
        assertTrue(requestDetails.contains("grant_type=authorization_code&code=***"))
        assertTrue(responseDetails.contains("""{"access_token":"***"}"""))
        assertFalse(entries.any { "secret" in it.message })
    }

    @Test
    fun `debug omits encoded bodies`() = runTest {
        configure(MobileSyncLogSeverity.DEBUG)
        val client = HttpClient(
            MockEngine {
                respond(
                    content = "compressed-bytes",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentEncoding, "gzip")
                )
            }
        ) {
            installHttpLogging(tag = "TestHttp")
        }

        client.get("https://example.com/v1/sync").bodyAsText()

        val responseDetails = entries.last().message
        assertTrue(responseDetails.contains("encoded"))
        assertFalse(responseDetails.contains("compressed-bytes"))
    }

    @Test
    fun `failed call logs a warning`() = runTest {
        configure(MobileSyncLogSeverity.INFO)
        val client = HttpClient(MockEngine { error("offline") }) {
            installHttpLogging(tag = "TestHttp")
        }

        runCatching { client.get("https://example.com/v1/sync") }

        val failure = entries.last()
        assertEquals(MobileSyncLogSeverity.WARN, failure.severity)
        assertTrue(failure.message.startsWith("<-- HTTP FAILED"))
    }

    @Test
    fun `error responses still log at info`() = runTest {
        configure(MobileSyncLogSeverity.INFO)
        val client = HttpClient(MockEngine { respondError(HttpStatusCode.Unauthorized) }) {
            installHttpLogging(tag = "TestHttp")
        }

        client.get("https://example.com/v1/sync").bodyAsText()

        assertEquals(MobileSyncLogSeverity.INFO, entries.last().severity)
        assertTrue(entries.last().message.startsWith("<-- 401 Unauthorized"))
    }
}
