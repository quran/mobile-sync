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

    @Test
    fun `prelive serves uploaded avatars from its own bucket`() {
        assertEquals(
            "https://s3.amazonaws.com/qreflect-bucket-prelive",
            AppEnvironment.PRELIVE.synchronizationEnvironment().avatarsHost
        )
    }

    @Test
    fun `production serves uploaded avatars from its bucket`() {
        assertEquals(
            "https://s3.amazonaws.com/qreflect-bucket",
            AppEnvironment.PRODUCTION.synchronizationEnvironment().avatarsHost
        )
    }
}
