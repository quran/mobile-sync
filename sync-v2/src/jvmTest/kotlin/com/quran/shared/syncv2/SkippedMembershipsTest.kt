package com.quran.shared.syncv2

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.quran.shared.syncv2.Device.Companion.FAVORITES
import com.quran.shared.syncv2.fake.FMembership
import com.quran.shared.syncv2.fake.FNote
import com.quran.shared.syncv2.fake.FReadingBookmark
import com.quran.shared.syncv2.fake.FakeSyncServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Items a type can't map are skipped, but never silently, and reported once per run: memberships only once their
 * parents are written.
 */
class SkippedMembershipsTest {
    private val lines = mutableListOf<Pair<Severity, String>>()
    private val writer = object : LogWriter() {
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            synchronized(lines) { lines += severity to message }
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
            assertTrue(lines.any { "$study-page7" in it.second }, lines.toString())
            assertTrue(lines.any { (severity, line) -> severity == Severity.Warn && "gone-$ayah" in line }, lines.toString())
        }
    }

    /**
     * Collections new in a GET are linked only when they are written, but the GET's memberships are also mapped
     * before that: by the collections' check for remote child edits, and by outbox resolution. Those passes don't
     * warn (warnings reach crash reporting); every membership here resolves once its collection is written.
     */
    @Test
    fun `memberships of collections new in the same GET log no warning`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val remote = Remote(server)
            remote.addToCollection(remote.createCollection("Study"), 2, 255)
            remote.bookmark(3, 1)
            device.sessions.addSession(1, 1, 1_760_000_000_000L)
            server.loseNextPostResponse = true

            assertIs<SyncResult.Failed>(device.sync(), "the first sync's POST lands, but its response is lost")
            remote.addToCollection(remote.createCollection("Memorize"), 2, 7)
            device.settle()

            assertEquals(setOf("2:255"), device.versesIn("Study"))
            assertEquals(setOf("2:7"), device.versesIn("Memorize"))
            assertEquals(setOf("3:1"), device.versesIn(FAVORITES))
            assertEquals(emptyList(), warnings())
        }
    }

    /** Outbox resolution maps the GET too; an unreadable note or reading bookmark still warns once, not twice. */
    @Test
    fun `an unreadable note or reading bookmark warns once when an outbox is resolved`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.sessions.addSession(1, 1, 1_760_000_000_000L)
            server.loseNextPostResponse = true
            assertIs<SyncResult.Failed>(device.sync(), "the first sync's POST lands, but its response is lost")
            val account = server.account(Device.ACCOUNT)
            val now = account.now()
            account.notes["badnote"] = FNote("A note body", listOf("not a range"), now, now)
            account.log("NOTE", "badnote", "CREATE")
            account.readingBookmarks["badslot"] = FReadingBookmark(2, null, "ayah", 1, null, "verses_6236", null, now, now)
            account.log("READING_BOOKMARK", "badslot", "CREATE")

            device.settle()

            assertEquals(emptyList(), device.noteBodies())
            listOf("badnote", "badslot").forEach { id ->
                assertEquals(1, warnings().count { id in it }, "$id: ${warnings()}")
            }
        }
    }

    /** Warnings so far, but the lost POST's own. */
    private fun warnings(): List<String> = synchronized(lines) {
        lines.filter { it.first >= Severity.Warn }.map { it.second }.filterNot { it.startsWith("Sync failed") }
    }
}
