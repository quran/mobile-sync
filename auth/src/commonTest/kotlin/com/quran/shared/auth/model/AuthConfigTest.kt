package com.quran.shared.auth.model

import kotlin.test.Test
import kotlin.test.assertEquals

class AuthConfigTest {

    @Test
    fun `prelive environment uses prelive base url`() {
        val config = AuthConfig(
            environment = AuthEnvironment.PRELIVE,
            clientId = "client-id"
        )

        assertEquals("https://prelive-oauth2.quran.foundation", config.baseUrl)
        assertEquals("https://prelive-oauth2.quran.foundation/oauth2/token", config.tokenEndpoint)
    }

    @Test
    fun `production environment uses production base url`() {
        val config = AuthConfig(
            environment = AuthEnvironment.PRODUCTION,
            clientId = "client-id"
        )

        assertEquals("https://oauth2.quran.foundation", config.baseUrl)
        assertEquals("https://oauth2.quran.foundation/oauth2/token", config.tokenEndpoint)
    }
}
