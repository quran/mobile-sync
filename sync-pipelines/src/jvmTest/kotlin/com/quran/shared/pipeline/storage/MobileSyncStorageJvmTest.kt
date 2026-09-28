package com.quran.shared.pipeline.storage

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MobileSyncStorageJvmTest {

    @Test
    fun `storage cache is scoped by requested directory`() = runTest {
        val directoryA = Files.createTempDirectory("mobile-sync-a").toFile()
        val directoryB = Files.createTempDirectory("mobile-sync-b").toFile()

        val storageA = createMobileSyncStorage(directoryA)
        val storageAAgain = createMobileSyncStorage(directoryA)
        val storageB = createMobileSyncStorage(directoryB)

        storageA.authSettings.putLong("test-key", 1L)
        storageB.authSettings.putLong("test-key", 2L)

        assertTrue(storageA === storageAAgain)
        assertTrue(storageA !== storageB)
        assertEquals(1L, storageAAgain.authSettings.getLong("test-key", 0L))
        assertEquals(2L, storageB.authSettings.getLong("test-key", 0L))
    }
}
