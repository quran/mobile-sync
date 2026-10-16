package com.quran.shared.syncv2

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.quran.shared.syncv2.fake.FakeSyncServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A collection's natural key is its name, user text that must not reach the logs: repairs name items by id. */
class UniquenessRepairLoggingTest {
    private val lines = mutableListOf<String>()
    private val writer = object : LogWriter() {
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            synchronized(lines) { lines += message }
        }
    }
    private var previousMinSeverity = Severity.Verbose

    @BeforeTest
    fun listen() {
        previousMinSeverity = Logger.config.minSeverity
        Logger.setMinSeverity(Severity.Verbose)
        Logger.addLogWriter(writer)
    }

    @AfterTest
    fun stop() {
        try {
            Logger.setLogWriters(Logger.config.logWriterList - writer)
        } finally {
            Logger.setMinSeverity(previousMinSeverity)
        }
    }

    @Test
    fun `reverting a local rename that clashes logs the ids, not the names`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val study = device.collections.addCollection("Zq9x study", t(0)).localId
            device.settle()
            device.collections.renameCollection(study, "Zq9x memorize", t(10))
            Remote(server).createCollection("Zq9x memorize")

            device.settle()

            assertEquals(setOf("Zq9x study", "Zq9x memorize"), device.collectionNames())
            synchronized(lines) {
                assertTrue(lines.any { "reverting" in it && it.endsWith("localId=$study") }, lines.toString())
                assertEquals(emptyList(), lines.filter { it.contains("Zq9x", ignoreCase = true) })
            }
        }
    }

    private fun t(n: Int) = 1_760_000_000_000L + n * 1_000L
}
