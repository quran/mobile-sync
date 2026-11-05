package com.quran.shared.logging

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
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

    // The failure summaries without their duration, which varies.
    private fun failureSummaries() = entries
        .filter { it.severity != MobileSyncLogSeverity.DEBUG && it.message.startsWith("<-- HTTP FAILED") }
        .map { it.severity to it.message.replace(Regex(""" \(\d+ms\)"""), "") }

    @Test
    fun `failed call logs a warning naming its request`() = runTest {
        configure(MobileSyncLogSeverity.INFO)
        val client = HttpClient(MockEngine { throw IllegalStateException("offline") }) {
            installHttpLogging(tag = "TestHttp")
        }

        runCatching { client.get("https://example.com/v1/sync?page=2") }

        assertEquals(
            listOf(
                MobileSyncLogSeverity.WARN to
                    "<-- HTTP FAILED GET https://example.com/v1/sync?page=2: ${IllegalStateException("offline")}"
            ),
            failureSummaries()
        )
        assertTrue(Regex("""^<-- HTTP FAILED GET \S+ \(\d+ms\): """).containsMatchIn(entries.last().message))
    }

    @Test
    fun `failed call with a multi-line cause names its request in the summary and logs the cause at debug`() =
        runTest {
            configure(MobileSyncLogSeverity.DEBUG)
            val cause = IllegalStateException("Code=-1005 \"The network connection was lost.\"\nUserInfo={...}")
            val client = HttpClient(MockEngine { throw cause }) {
                installHttpLogging(tag = "TestHttp")
            }

            runCatching { client.post("https://example.com/v1/sync") }

            val (summary, details) = entries.takeLast(2)
            assertEquals(MobileSyncLogSeverity.WARN, summary.severity)
            assertTrue(summary.message.startsWith("<-- HTTP FAILED POST https://example.com/v1/sync ("))
            assertTrue(summary.message.endsWith("\"The network connection was lost.\""))
            assertEquals(MobileSyncLogSeverity.DEBUG, details.severity)
            assertEquals("${summary.message}\nUserInfo={...}", details.message)
            assertEquals(1, failureSummaries().size)
        }

    @Test
    fun `failed call redacts its url`() = runTest {
        configure(MobileSyncLogSeverity.DEBUG)
        val secret = Regex("secret-[a-z]+")
        val client = HttpClient(MockEngine { throw IllegalStateException("offline") }) {
            installHttpLogging(tag = "TestHttp", redact = { it.replace(secret, "***") })
        }

        runCatching { client.get("https://example.com/token?code=secret-code") }

        assertEquals(
            listOf(
                MobileSyncLogSeverity.WARN to
                    "<-- HTTP FAILED GET https://example.com/token?code=***: ${IllegalStateException("offline")}"
            ),
            failureSummaries()
        )
        assertFalse(entries.any { "secret" in it.message })
    }

    @Test
    fun `concurrent failed calls each name their own request`() = runTest {
        configure(MobileSyncLogSeverity.INFO)
        // Both calls are in flight before either fails.
        val started = mapOf("/first" to CompletableDeferred<Unit>(), "/second" to CompletableDeferred<Unit>())
        val client = HttpClient(
            MockEngine { request ->
                started.getValue(request.url.encodedPath).complete(Unit)
                started.values.forEach { it.await() }
                throw IllegalStateException("offline ${request.url.encodedPath}")
            }
        ) {
            installHttpLogging(tag = "TestHttp")
        }

        val first = launch { runCatching { client.get("https://example.com/first") } }
        val second = launch { runCatching { client.get("https://example.com/second") } }
        first.join()
        second.join()

        assertEquals(
            setOf(
                MobileSyncLogSeverity.WARN to
                    "<-- HTTP FAILED GET https://example.com/first: ${IllegalStateException("offline /first")}",
                MobileSyncLogSeverity.WARN to
                    "<-- HTTP FAILED GET https://example.com/second: ${IllegalStateException("offline /second")}",
            ),
            failureSummaries().toSet()
        )
        assertEquals(2, failureSummaries().size)
    }

    @Test
    fun `timed out call logs a warning naming its request`() = runTest {
        configure(MobileSyncLogSeverity.INFO)
        // HttpTimeout times the request on the client's own dispatcher, in real time, not runTest's virtual time.
        // The engine never answers, so the timeout always fires.
        val client = HttpClient(MockEngine { awaitCancellation() }) {
            installHttpLogging(tag = "TestHttp")
            install(HttpTimeout) { requestTimeoutMillis = 50 }
        }

        val result = runCatching { client.post("https://example.com/v1/sync") }

        assertTrue(result.exceptionOrNull() is HttpRequestTimeoutException)
        val (severity, message) = failureSummaries().single()
        assertEquals(MobileSyncLogSeverity.WARN, severity)
        assertTrue(message.startsWith("<-- HTTP FAILED POST https://example.com/v1/sync: "), message)
        assertTrue("HttpRequestTimeoutException: Request timeout has expired" in message, message)
    }

    @Test
    fun `cancelled call logs at info naming its request`() = runTest {
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

        val failures = failureSummaries()
        assertEquals(1, failures.size)
        val (severity, message) = failures.single()
        assertEquals(MobileSyncLogSeverity.INFO, severity)
        assertTrue(message.startsWith("<-- HTTP FAILED GET https://example.com/v1/sync: "))
        assertTrue("CancellationException" in message)
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
