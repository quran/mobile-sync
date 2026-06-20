package com.quran.shared.pipeline.di

import com.quran.shared.auth.model.AuthRuntimeConfig
import com.quran.shared.pipeline.AppEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthRuntimeConfigForClientIdTest {
    @Test
    fun `blank client id maps to unconfigured auth`() {
        val runtimeConfig = authRuntimeConfigForClientId(
            appEnvironment = AppEnvironment.PRELIVE,
            clientId = "   ",
            clientSecret = null
        )

        assertEquals(AuthRuntimeConfig.Unconfigured, runtimeConfig)
    }

    @Test
    fun `nonblank client id maps to configured auth`() {
        val runtimeConfig = authRuntimeConfigForClientId(
            appEnvironment = AppEnvironment.PRELIVE,
            clientId = " client-id ",
            clientSecret = "client-secret"
        )

        assertTrue(runtimeConfig is AuthRuntimeConfig.Configured)
        assertEquals("client-id", runtimeConfig.config.clientId)
        assertEquals("client-secret", runtimeConfig.config.clientSecret)
        assertEquals(AppEnvironment.PRELIVE.authEnvironment, runtimeConfig.config.environment)
    }
}
