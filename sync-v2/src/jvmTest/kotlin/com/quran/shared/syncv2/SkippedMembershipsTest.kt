package com.quran.shared.syncv2

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.quran.shared.syncv2.fake.FMembership
import com.quran.shared.syncv2.fake.FakeSyncServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Memberships the verse type can't map are skipped, but never silently. */
class SkippedMembershipsTest {
    private val lines = mutableListOf<String>()
    private val writer = object : LogWriter() {
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            synchronized(lines) { lines += message }
        }
    }

    @BeforeTest
    fun listen() = Logger.addLogWriter(writer)

    @AfterTest
    fun stop() = Logger.setLogWriters(Logger.config.logWriterList - writer)

    @Test
    fun `a membership of an unknown bookmark or collection is skipped and logged`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val study = Remote(server).createCollection("Study")
            val ayah = Remote(server).bookmark(2, 255)
            val account = server.account(Device.ACCOUNT)
            // A page bookmark (mobile syncs ayah bookmarks only), and a collection GET never mentions.
            listOf("$study-page7", "gone-$ayah").forEach { id ->
                val (collectionId, bookmarkId) = id.split("-")
                account.memberships[id] = FMembership(collectionId, bookmarkId, account.now())
                account.log("COLLECTION_BOOKMARK", id, "CREATE")
            }

            device.settle()

            assertEquals(emptySet(), device.versesIn("Study"))
            assertTrue(lines.any { "$study-page7" in it }, lines.toString())
            assertTrue(lines.any { "gone-$ayah" in it }, lines.toString())
        }
    }
}
