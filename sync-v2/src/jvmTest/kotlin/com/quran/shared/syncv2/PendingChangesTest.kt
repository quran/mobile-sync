package com.quran.shared.syncv2

import app.cash.sqldelight.Query
import com.quran.shared.syncv2.fake.FakeSyncServer
import com.quran.shared.syncv2.types.CollectionAyahsType
import com.quran.shared.syncv2.types.syncTypes
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [SyncEngineV2.unsynced]'s pending items: the items the next push sends. */
class PendingChangesTest {
    private fun t(n: Int) = 1_760_000_000_000L + n * 1_000L

    private fun Device.pending() = SyncEngineV2.unsynced(database, syncTypes(database)).pending

    /** The planner restores a delete the server would refuse; counting it must leave the delete in place. */
    @Test
    fun `a refused delete is not pending, and counting it writes nothing`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val highlight = "${Device.HIGHLIGHTS}blue"
            device.highlight(2, 255, "blue", t(0))
            device.settle()
            val id = device.collectionId(highlight)
            device.database.collectionsQueries.collectionAyahsDeleteIn(id)
            device.database.collectionsQueries.collectionDelete(id)
            var heard = 0
            device.database.sync_itemQueries.holdInputs().addListener(Query.Listener { heard++ })

            assertEquals(emptyList(), device.pending())

            assertFalse(highlight in device.collectionNames(), "not restored")
            assertEquals(0, heard, "no write for a listener to hear")
        }
    }

    /** Every write kind the planner queues, a verse waiting on a released hold, and a swap through a temporary name. */
    @Test
    fun `the preview plans what the planner plans`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.highlight(2, 255, "blue", t(0))
            val fixed = device.addNote("12345", "1:1", t(1))
            val broken = device.addNote("A note to break", "1:2", t(2))
            val deleted = device.addNote("A note to break and delete", "1:3", t(3))
            val long = device.collections.addCollection("x".repeat(256), t(4)).localId
            device.collections.addAyah(long, 2, 255, t(5))
            val covered = device.collections.addCollection("Covered", t(6)).localId
            device.collections.addAyah(covered, 3, 1, t(7))
            val a = device.collections.addCollection("A", t(8)).localId
            val b = device.collections.addCollection("B", t(9)).localId
            device.settle()
            device.notes.updateNote(deleted, "1234", t(10))
            device.settle()
            val store = device.engine.store
            val types = syncTypes(device.database)
            val ayahs = types.filterIsInstance<CollectionAyahsType>().single()
            val coveredVerse = store.rows("COLLECTION_AYAH").filterValues { ayahs.verseOf(it.base!!).collectionLocalId == covered }
                .keys.single()
            device.notes.deleteNote(deleted)
            val highlight = device.collectionId("${Device.HIGHLIGHTS}blue")
            device.database.collectionsQueries.collectionAyahsDeleteIn(highlight)
            device.database.collectionsQueries.collectionDelete(highlight)
            device.notes.updateNote(fixed, "Long enough now", t(11))
            device.notes.updateNote(broken, "1234", t(12))
            device.collections.renameCollection(long, "Fixed", t(13))
            // Its delete landed; its verse's tombstone is left.
            device.collections.deleteCollection(covered)
            store.remove("COLLECTION", covered)
            device.collections.renameCollection(a, "tmp", t(14))
            device.collections.renameCollection(b, "A", t(15))
            device.collections.renameCollection(a, "B", t(16))
            val study = device.collections.addCollection("Study", t(17)).localId
            device.collections.addAyah(study, 1, 1, t(18))
            val planner = PushPlanner(store, types, StoreRefs(store))
            fun PushPlan.items() = planned.map { listOf(it.type.name, it.localId, it.kind, it.mutation) } to deferred

            val preview = planner.preview().items()
            val plan = device.database.transactionWithResult { planner.plan(preview = false).items() }

            assertEquals(plan, preview)
            assertEquals(2, preview.second.size, "the verses of Fixed and Study wait for their collection")
            assertTrue(preview.first.any { (it[3] as OutgoingMutation).data?.get("name")?.jsonPrimitive?.content == "B (2)" })
            assertEquals("BODY_LENGTH", store.row("NOTE", broken)?.heldReason, "the planner wrote a hold")
            assertNull(store.row("NOTE", fixed)?.heldReason, "and released one")
            assertNull(store.row("COLLECTION", long), "and released the collection's")
            assertNull(store.row("COLLECTION_AYAH", coveredVerse), "and forgot the covered tombstone")
            assertTrue("${Device.HIGHLIGHTS}blue" in device.collectionNames(), "and restored the refused delete")
        }
    }

    /** Only the collection's DELETE is sent: the server cascades it to the verses. */
    @Test
    fun `deleting a synced collection with verses is one pending change`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val study = device.collections.addCollection("Study", t(0)).localId
            device.collections.addAyah(study, 2, 255, t(1))
            device.collections.addAyah(study, 1, 1, t(2))
            device.settle()

            device.collections.deleteCollection(study)

            assertEquals(listOf(ItemRef("COLLECTION", study)), device.pending())
        }
    }

    @Test
    fun `items held or waiting on a held item are not pending`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val long = device.collections.addCollection("x".repeat(256), t(0)).localId
            device.collections.addAyah(long, 2, 255, t(1))

            assertEquals(2, device.settle().held.size)
            assertEquals(emptyList(), device.pending())
        }
    }

    /** The POST may have landed: its items stay pending until a GET resolves its outbox. */
    @Test
    fun `a batch whose response was lost stays pending until a GET resolves it`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.settle()
            val note = device.addNote("Sent, its answer lost", "1:1", t(0))
            server.loseNextPostResponse = true

            assertIs<SyncResult.Failed>(device.sync())

            assertEquals(listOf("Sent, its answer lost"), Remote(server).noteBodies(), "it landed")
            assertEquals(listOf(ItemRef("NOTE", note)), device.pending())
            device.syncOk()
            assertEquals(emptyList(), device.pending())
        }
    }
}
