package com.quran.shared.pipeline

import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.get
import com.russhwolf.settings.set
import java.util.Properties
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SettingsLocalModificationDateFetcherTest {

    @Test
    fun `first cursor protocol use replays from zero and marks completion only on success`() = runBlocking {
        val settings = PropertiesSettings(Properties())
        settings[LAST_MODIFIED_KEY] = 900L
        settings["pending.local.mutation"] = "preserved"
        val fetcher = SettingsLocalModificationDateFetcher(settings)

        assertEquals(0L, fetcher.localLastModificationDate())
        assertEquals(0L, settings.getLong(LAST_MODIFIED_KEY, -1L))
        assertEquals("preserved", settings.getString("pending.local.mutation", ""))
        assertFalse(settings.hasKey(PROTOCOL_VERSION_KEY))

        fetcher.updateLastModificationDate(1_200L)

        assertEquals(1_200L, settings.getLong(LAST_MODIFIED_KEY, -1L))
        assertEquals(1, settings.getInt(PROTOCOL_VERSION_KEY, 0))
        assertEquals(
            1_200L,
            SettingsLocalModificationDateFetcher(settings).localLastModificationDate()
        )
    }

    @Test
    fun `failed replay remains incomplete and retries from zero`() = runBlocking {
        val settings = PropertiesSettings(Properties())
        settings[LAST_MODIFIED_KEY] = 900L
        val fetcher = SettingsLocalModificationDateFetcher(settings)

        assertEquals(0L, fetcher.localLastModificationDate())
        assertFalse(settings.hasKey(PROTOCOL_VERSION_KEY))

        settings[LAST_MODIFIED_KEY] = 777L

        assertEquals(0L, fetcher.localLastModificationDate())
        assertEquals(0L, settings.getLong(LAST_MODIFIED_KEY, -1L))
        assertFalse(settings.hasKey(PROTOCOL_VERSION_KEY))
    }

    private companion object {
        const val LAST_MODIFIED_KEY = "com.quran.sync.last_modified_date"
        const val PROTOCOL_VERSION_KEY = "com.quran.sync.protocol_version"
    }
}
