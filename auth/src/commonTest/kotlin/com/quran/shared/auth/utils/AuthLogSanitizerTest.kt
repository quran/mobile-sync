package com.quran.shared.auth.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthLogSanitizerTest {

    @Test
    fun redactsSensitiveHeadersAndBodyFields() {
        val sensitiveValue = "sensitive-value"
        val message = """
            -> Authorization: Bearer $sensitiveValue
            -> x-auth-token: $sensitiveValue
            BODY START
            token=$sensitiveValue&code=$sensitiveValue&code_verifier=$sensitiveValue
            {"access_token":"$sensitiveValue","id_token":"$sensitiveValue","refresh_token":"$sensitiveValue"}
            BODY END
        """.trimIndent()

        val result = redactSensitiveAuthData(message)

        assertFalse(result.contains(sensitiveValue))
        assertTrue(result.contains("-> Authorization: ***"))
        assertTrue(result.contains("token=***&code=***&code_verifier=***"))
        assertTrue(result.contains(""""access_token":"***""""))
    }
}
