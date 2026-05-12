package com.quran.shared.pipeline

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncSettingsLocalModificationDateStoreTest {

    @Test
    fun `last modification date defaults to zero and persists updates`() = runTest {
        val store = SyncSettingsLocalModificationDateStore(MapSettings().toSuspendSettings())

        assertEquals(0L, store.localLastModificationDate())

        store.updateLastModificationDate(1234L)

        assertEquals(1234L, store.localLastModificationDate())
    }
}
