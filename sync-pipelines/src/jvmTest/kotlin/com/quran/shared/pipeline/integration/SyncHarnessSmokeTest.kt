package com.quran.shared.pipeline.integration

import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Smoke tests for the integration harness. */
class SyncHarnessSmokeTest {

    @Test
    fun `first sync of an empty account downloads only Favorites`() = syncScenario {
        val phone = device("phone")

        val run = phone.login(ALICE)

        assertSucceeded(run)
        assertEquals(1, run.gets)
        assertEquals(0, run.posts)
        val collections = phone.data.collectionsWithBookmarks.first()
        assertEquals(listOf("Favorites"), collections.map { it.collection.name })
        assertTrue(collections.single().collection.isDefault)
        assertConverged(ALICE, phone)
    }

    private companion object {
        const val ALICE = "alice"
    }
}
