package com.quran.shared.auth.utils

import com.quran.shared.logging.MobileSyncLogSeverity
import com.quran.shared.logging.MobileSyncLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parameters
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthHttpLoggingTest {
    private val messages = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        MobileSyncLogging.configure(sink = { _, _, _ -> }, minSeverity = MobileSyncLogSeverity.VERBOSE)
    }

    @Test
    fun `debug logs of a token refresh keep every token out`() = runTest {
        MobileSyncLogging.configure(
            sink = { _, tag, message -> if (tag == "AuthHttp") messages += message },
            minSeverity = MobileSyncLogSeverity.DEBUG
        )
        val secrets = listOf("client-credentials", "old-refresh", "new-access", "new-id", "new-refresh")
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"access_token":"new-access","id_token":"new-id","refresh_token":"new-refresh",""" +
                        """"token_type":"Bearer","expires_in":3600}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
        ) {
            installAuthHttpLogging()
        }

        client.submitForm(
            url = "https://auth.example.com/oauth2/token",
            formParameters = parameters {
                append("grant_type", "refresh_token")
                append("client_id", "quran-ios")
                append("refresh_token", "old-refresh")
                append("scope", "openid")
            }
        ) {
            header(HttpHeaders.Authorization, "Basic client-credentials")
        }.bodyAsText()

        // A summary line and its details for both the request and the response.
        assertEquals(4, messages.size)
        val leaks = messages.filter { message -> secrets.any { it in message } }
        assertTrue(leaks.isEmpty(), "Leaked tokens: $leaks")
        assertTrue(messages.any { "grant_type=refresh_token&client_id=quran-ios&refresh_token=***" in it })
        assertTrue(messages.any { """"refresh_token":"***"""" in it })
    }
}
