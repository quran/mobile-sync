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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
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
    fun `failed call warns with only the exception class and logs the whole line at debug`() = runTest {
        configure(MobileSyncLogSeverity.DEBUG)
        // Like an NSError on iOS, its message holds the failing URL with its query.
        val offline = IOException("Offline: https://example.com/callback?code=secret-code&state=secret-state")
        val client = HttpClient(MockEngine { throw offline }) {
            installHttpLogging(tag = "TestHttp")
        }

        runCatching { client.get("https://example.com/v1/sync") }

        val failures = entries.filter { it.message.startsWith("<-- HTTP FAILED") }
        assertEquals(
            listOf(MobileSyncLogSeverity.WARN to "<-- HTTP FAILED: IOException", MobileSyncLogSeverity.DEBUG to "<-- HTTP FAILED: $offline"),
            failures.map { it.severity to it.message }
        )
        assertFalse(entries.any { it.severity >= MobileSyncLogSeverity.INFO && "secret" in it.message }, "$entries")
    }

    @Test
    fun `cancelled call logs at info`() = runTest {
        configure(MobileSyncLogSeverity.INFO)
        val started = CompletableDeferred<Unit>()
        val client = HttpClient(
            MockEngine {
                started.complete(Unit)
                awaitCancellation()
            }
        ) {
            installHttpLogging(tag = "TestHttp")
        }

        val call = launch { client.get("https://example.com/v1/sync") }
        started.await()
        call.cancelAndJoin()

        val failure = entries.last()
        assertEquals(MobileSyncLogSeverity.INFO, failure.severity)
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
