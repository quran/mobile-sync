package com.quran.shared.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals

class AppEnvironmentTest {

    @Test
    fun `prelive app environment maps to prelive sync endpoint`() {
        assertEquals(
            "https://apis-prelive.quran.foundation/auth",
            AppEnvironment.PRELIVE.synchronizationEnvironment().endPointURL
        )
    }

    @Test
    fun `production app environment maps to production sync endpoint`() {
        assertEquals(
            "https://apis.quran.foundation/auth",
            AppEnvironment.PRODUCTION.synchronizationEnvironment().endPointURL
        )
    }
}
