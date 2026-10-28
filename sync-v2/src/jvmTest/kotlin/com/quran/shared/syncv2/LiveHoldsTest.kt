package com.quran.shared.syncv2

import app.cash.sqldelight.Query
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.quran.shared.syncv2.db.SyncV2Database
import com.quran.shared.syncv2.fake.FakeSyncServer
import com.quran.shared.syncv2.fake.HttpError
import com.quran.shared.syncv2.types.syncTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Holds.list] as a live view between syncs ([SyncEngineV2.held] without an engine): an edit or delete that would
 * release a hold on the next push takes the item, and what waited on it, out at once.
 */
class LiveHoldsTest {
    private fun t(n: Int) = 1_760_000_000_000L + n * 1_000L

    private fun Device.liveHeld() = SyncEngineV2.held(database, syncTypes(database))

    /** The next push validates the edit and holds the note again, so it stays listed until an edit makes it valid. */
    @Test
    fun `an edit that leaves a held note invalid keeps it held`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val short = device.addNote("12345", "1:1", t(0))
            assertEquals(listOf(HeldItem("NOTE", short, "BODY_LENGTH")), device.settle().held)

            device.notes.updateNote(short, "1234", t(1))

            assertEquals(listOf(HeldItem("NOTE", short, "BODY_LENGTH")), device.liveHeld(), "still too short")
            assertEquals(listOf(HeldItem("NOTE", short, "BODY_LENGTH")), device.settle().held, "the next push agrees")
            device.notes.updateNote(short, "Long enough now", t(2))
            assertEquals(emptyList(), device.liveHeld())
        }
    }

    /** A server hold ends with an edit, but an edit the server would refuse is held again for its own reason. */
    @Test
    fun `an invalid edit of a server-held note is held for the new reason`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            server.rejectMutation = { HttpError(422, "ValidationError", "refused") }
            val note = device.addNote("Refused by the server", "1:1", t(0))
            assertEquals(listOf(HeldItem("NOTE", note, "422 ValidationError")), device.settle().held)

            device.notes.updateNote(note, "1234", t(1))

            assertEquals(listOf(HeldItem("NOTE", note, "BODY_LENGTH")), device.liveHeld())
        }
    }

    @Test
    fun `a deleted never-synced held note is no longer listed`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val short = device.addNote("12345", "1:1", t(0))
            assertEquals(1, device.settle().held.size)

            device.notes.deleteNote(short)

            assertEquals(emptyList(), device.liveHeld())
        }
    }

    @Test
    fun `fixing a held collection releases the verses waiting on it`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val long = device.collections.addCollection("x".repeat(256), t(0)).localId
            device.collections.addAyah(long, 2, 255, t(1))
            assertEquals(listOf("NAME_LENGTH", HeldItem.WAITING_ON_PARENT), device.settle().held.map { it.reason })

            device.collections.renameCollection(long, "Study", t(2))

            assertEquals(emptyList(), device.liveHeld())
        }
    }

    /** Favorites waits for a GET to link it; before any account syncs, that is not a hold to report. */
    @Test
    fun `never-linked items are listed only once an account is set`() = FakeSyncServer().use { server ->
        server.omitDefaultCollection = true
        Device(server).use { device ->
            val favorites = device.collections.favorites().localId
            device.collections.addAyah(favorites, 1, 1, t(0))
            assertEquals(emptyList(), device.liveHeld(), "never synced")

            device.settle()

            assertEquals(listOf(HeldItem.NEVER_LINKED, HeldItem.WAITING_ON_PARENT), device.liveHeld().map { it.reason })
            SyncEngineV2.wipe(device.database, syncTypes(device.database))
            assertEquals(emptyList(), device.liveHeld(), "wiped")
        }
    }

    /**
     * `holdInputs` must read every table a hold depends on, so its listeners hear each write that can hold or release
     * an item: every table but the sync history.
     */
    @Test
    fun `holdInputs hears a write to every table but the history`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { SyncV2Database.Schema.create(it) }
        driver.use {
            val database = SyncV2Database(driver)
            val tables = driver.executeQuery(null, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'", { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.next().value) names += cursor.getString(0)!!
                app.cash.sqldelight.db.QueryResult.Value(names)
            }, 0).value
            val writes = mapOf<String, () -> Unit>(
                "sync_item" to { database.sync_itemQueries.upsert("NOTE", 1, null, null, "BODY_LENGTH", null, null) },
                "sync_meta" to { database.sync_itemQueries.metaSet("account", "a") },
                "collection" to { database.collectionsQueries.collectionInsert("Study", "study", 0, 0, 0, 0, 0) },
                "collection_ayah" to { database.collectionsQueries.collectionAyahInsert(1, 1, 1, 0) },
                "remote_ayah_bookmark" to { database.collectionsQueries.remoteAyahBookmarkPut("b", 1, 1) },
                "note" to { database.notesQueries.insert("A note body", 0, 0, null) },
                "note_range" to { database.notesQueries.insertRange(1, 0, 1, 1, 1, 1) },
                "reading_session" to { database.sessionsQueries.insert(1, 1, 0, 0) },
                "reading_bookmark" to { database.readingBookmarksQueries.insert(1, null, null, null, null, null, null, 0, 0) }
            )
            assertEquals(tables - "sync_run", writes.keys, "a table a hold may depend on has no write below")

            var heard = 0
            database.sync_itemQueries.holdInputs().addListener(Query.Listener { heard++ })
            writes.forEach { (table, write) ->
                val before = heard
                write()
                assertTrue(heard > before, "a write to $table")
            }
        }
    }
}
