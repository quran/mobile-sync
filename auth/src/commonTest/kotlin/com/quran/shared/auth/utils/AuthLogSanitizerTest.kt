package com.quran.shared.auth.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthLogSanitizerTest {

    @Test
    fun redactsSensitiveHeadersAndBodyFields() {
        val sensitiveValue = "sensitive-value"
        val message = """
            --> POST https://auth.example.com/oauth2/token
            Authorization: Bearer $sensitiveValue
            x-auth-token: $sensitiveValue
            Cookie: session=$sensitiveValue

            token=$sensitiveValue&code=$sensitiveValue&code_verifier=$sensitiveValue&refresh_token=$sensitiveValue&access_token=$sensitiveValue&id_token=$sensitiveValue&id_token_hint=$sensitiveValue&client_secret=$sensitiveValue
            --> END POST
        """.trimIndent()

        val result = redactSensitiveAuthData(message)

        assertFalse(result.contains(sensitiveValue))
        assertTrue(result.contains("Authorization: ***"))
        assertTrue(result.contains("x-auth-token: ***"))
        assertTrue(result.contains("Cookie: ***"))
        assertTrue(
            result.contains(
                "token=***&code=***&code_verifier=***&refresh_token=***&access_token=***" +
                    "&id_token=***&id_token_hint=***&client_secret=***"
            )
        )
    }

    @Test
    fun redactsSensitiveJsonFields() {
        val sensitiveValue = "sensitive-value"
        val message = """
            <-- 200 OK https://auth.example.com/oauth2/token (12ms)

            {"access_token":"$sensitiveValue","id_token":"$sensitiveValue","refresh_token":"$sensitiveValue","client_secret":"$sensitiveValue","token_type":"Bearer"}
            <-- END HTTP (12ms, 140-byte body)
        """.trimIndent()

        val result = redactSensitiveAuthData(message)

        assertFalse(result.contains(sensitiveValue))
        assertTrue(result.contains(""""access_token":"***""""))
        assertTrue(result.contains(""""refresh_token":"***""""))
        assertTrue(result.contains(""""token_type":"Bearer""""))
    }

    @Test
    fun redactsSensitiveQueryParameters() {
        val message = "--> GET https://auth.example.com/logout?id_token_hint=sensitive-value&state=abc (5ms)"

        val result = redactSensitiveAuthData(message)

        assertEquals("--> GET https://auth.example.com/logout?id_token_hint=***&state=abc (5ms)", result)
    }

    @Test
    fun emptyHeaderValueKeepsTheNextLine() {
        val message = "Authorization:\nnext-line"

        assertEquals("Authorization: ***\nnext-line", redactSensitiveAuthData(message))
    }

    @Test
    fun keepsValuesAndKeysThatOnlyContainSensitiveNames() {
        val message = "grant_type=refresh_token&code_challenge=challenge&token_type_hint=refresh_token&scope=openid"

        assertEquals(message, redactSensitiveAuthData(message))
    }
}
