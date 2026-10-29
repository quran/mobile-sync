package com.quran.shared.pipeline.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which sync attempts fetch the user's profile now, and which only when it is due. */
class ProfileFetchTest {
    @Test
    fun `a manual sync fetches the profile now`() {
        assertEquals(ProfileFetch.NOW, SyncAttempt(SyncTrigger.IMMEDIATE, number = 0).profileFetch())
    }

    @Test
    fun `a manual sync's retry fetches only when due`() {
        assertEquals(ProfileFetch.IF_DUE, SyncAttempt(SyncTrigger.IMMEDIATE, number = 1).profileFetch())
    }

    @Test
    fun `automatic runs fetch only when due`() {
        val automatic = SyncTrigger.entries - SyncTrigger.IMMEDIATE
        automatic.forEach { trigger ->
            assertEquals(ProfileFetch.IF_DUE, SyncAttempt(trigger, number = 0).profileFetch(), "$trigger")
        }
    }
}
