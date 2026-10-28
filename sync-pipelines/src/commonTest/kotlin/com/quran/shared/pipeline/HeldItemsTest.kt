package com.quran.shared.pipeline

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.quran.shared.mutations.ServerTextLimit
import com.quran.shared.syncv2.HeldItem
import com.quran.shared.syncv2.SyncEngineV2
import com.quran.shared.syncv2.SyncRow
import com.quran.shared.syncv2.SyncStore
import com.quran.shared.syncv2.types.CollectionsRepository
import com.quran.shared.syncv2.types.NoteRange
import com.quran.shared.syncv2.types.NotesRepository
import com.quran.shared.syncv2.types.syncTypes
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** [HeldItems]: the engine's held items as the public API shows them, the reason mapping, and the snapshot they share. */
class HeldItemsTest {
    private val database = inMemoryMobileSyncDatabase().synced
    /** What escaped the shared reader: on a device, a crash. */
    private val uncaught = Channel<Throwable>(Channel.UNLIMITED)
    private val sharing = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught.trySend(e) })
    private val held = HeldItems(database, sharing)
    private val store = SyncStore(database)
    private val notes = NotesRepository(database)
    private val collections = CollectionsRepository(database)

    @AfterTest
    fun tearDown() {
        sharing.cancel()
    }

    /** Holds [localId] of [type] as a push would: the reason with the item's current state. */
    private fun hold(type: String, localId: Long, reason: String, remoteId: String? = null) {
        val state = syncTypes(database).single { it.name == type }.readAll().getValue(localId)
        store.put(type, SyncRow(localId, remoteId, base = null, heldReason = reason, heldState = state))
    }

    private fun item(
        type: SyncResourceType,
        id: String,
        reason: HeldReason,
        collectionId: String? = null,
        waitingOn: HeldSyncItem? = null,
        httpStatus: Int? = null,
        serverCode: String? = null
    ) = HeldSyncItem(type, id, collectionId, reason, waitingOn, httpStatus, serverCode)

    @Test
    fun `a length hold is too short or too long by the text it measured`() {
        val short = notes.addNote("12345", listOf(NoteRange(1, 1, 1, 1)), 0)
        val long = notes.addNote("a".repeat(10_001), listOf(NoteRange(1, 2, 1, 2)), 1)
        val empty = collections.addCollection("x", 2).localId
        database.collectionsQueries.collectionUpdate("  ", "", 0, 0, 3, empty)
        val longName = collections.addCollection("y".repeat(256), 4).localId
        listOf(short, long).forEach { hold("NOTE", it, "BODY_LENGTH") }
        listOf(empty, longName).forEach { hold("COLLECTION", it, "NAME_LENGTH") }

        assertEquals(
            listOf(
                item(SyncResourceType.COLLECTION, "$empty", HeldReason.TOO_SHORT),
                item(SyncResourceType.COLLECTION, "$longName", HeldReason.TOO_LONG),
                item(SyncResourceType.NOTE, "$short", HeldReason.TOO_SHORT),
                item(SyncResourceType.NOTE, "$long", HeldReason.TOO_LONG)
            ),
            held.read().held
        )
    }

    /** Without its row, a length hold is measured by the state it recorded, not guessed. */
    @Test
    fun `a length hold on a deleted item is measured by its held state`() {
        val short = notes.addNote("12345", listOf(NoteRange(1, 1, 1, 1)), 0)
        val long = collections.addCollection("y".repeat(256), 1).localId
        val noteState = syncTypes(database).single { it.name == "NOTE" }.readAll().getValue(short)
        val collectionState = syncTypes(database).single { it.name == "COLLECTION" }.readAll().getValue(long)
        // Tombstones that hold their base, as a held DELETE does, so the engine still lists them.
        store.put("NOTE", SyncRow(short, "remote-note", noteState, "BODY_LENGTH", noteState))
        store.put("COLLECTION", SyncRow(long, "remote-collection", collectionState, "NAME_LENGTH", collectionState))
        notes.deleteNote(short)
        database.collectionsQueries.collectionDelete(long)

        assertEquals(
            listOf(item(SyncResourceType.COLLECTION, "$long", HeldReason.TOO_LONG), item(SyncResourceType.NOTE, "$short", HeldReason.TOO_SHORT)),
            held.read().held
        )
    }

    @Test
    fun `a length hold whose text can't be read measures it as empty`() {
        assertEquals(PublicHold(HeldReason.TOO_SHORT), heldReason("BODY_LENGTH") { HeldText(null, ServerTextLimit.NOTE_BODY) })
        assertEquals(PublicHold(HeldReason.TOO_SHORT), heldReason("NAME_LENGTH") { HeldText(null, ServerTextLimit.COLLECTION_NAME) })
        assertEquals(PublicHold(HeldReason.REJECTED_BY_SERVER, unknown = true), heldReason("BODY_LENGTH") { null }, "no known text")
    }

    @Test
    fun `every engine reason maps to a public one`() {
        fun map(reason: String, text: HeldText? = null) = heldReason(reason) { text }
        assertEquals(
            listOf(
                PublicHold(HeldReason.TOO_LONG),
                PublicHold(HeldReason.INVALID_RANGE),
                PublicHold(HeldReason.INVALID_VERSE),
                PublicHold(HeldReason.INVALID_LOCATION),
                PublicHold(HeldReason.INVALID_SLOT),
                PublicHold(HeldReason.TOO_MUCH_METADATA),
                PublicHold(HeldReason.RESERVED_NAME),
                PublicHold(HeldReason.READ_ONLY),
                PublicHold(HeldReason.WAITING_ON_PARENT),
                PublicHold(HeldReason.WAITING_ON_OTHER_ITEM),
                PublicHold(HeldReason.NOT_YET_LINKED),
                PublicHold(HeldReason.REJECTED_BY_SERVER, 409, "UniqueViolationError"),
                PublicHold(HeldReason.REJECTED_BY_SERVER, 422, "ValidationError"),
                PublicHold(HeldReason.REJECTED_BY_SERVER, 500, null)
            ),
            listOf(
                // A reading bookmark name has only a maximum.
                map("NAME_LENGTH", HeldText(null, null)),
                map("INVALID_RANGE"),
                map("INVALID_VERSE"),
                map("INVALID_LOCATION"),
                map("INVALID_SLOT"),
                map("METADATA_SIZE"),
                map("RESERVED_NAME"),
                map("READ_ONLY"),
                map(HeldItem.WAITING_ON_PARENT),
                map(HeldItem.WAITING_ON_KEY),
                map(HeldItem.NEVER_LINKED),
                map("409 UniqueViolationError"),
                map("422 ValidationError"),
                map("500")
            )
        )
    }

    @Test
    fun `an unknown reason is reported as rejected without a status and never crashes`() {
        listOf("SOMETHING_NEW", "", "4091 Code", "409 two words", "BODY_LENGTH ").forEach { reason ->
            assertEquals(PublicHold(HeldReason.REJECTED_BY_SERVER, unknown = true), heldReason(reason) { null }, reason)
        }
    }

    @Test
    fun `a membership is named by its verse and collection and waits on its held collection`() {
        val study = collections.addCollection("system:study", 0).localId
        collections.addAyah(study, 2, 255, 1)
        hold("COLLECTION", study, "RESERVED_NAME")

        val collection = item(SyncResourceType.COLLECTION, "$study", HeldReason.RESERVED_NAME)
        assertEquals(
            listOf(
                collection,
                item(SyncResourceType.COLLECTION_BOOKMARK, "2:255", HeldReason.WAITING_ON_PARENT, "$study", waitingOn = collection)
            ),
            held.read().held
        )
    }

    @Test
    fun `a held delete of a membership is named by its base`() {
        val study = collections.addCollection("Study", 0).localId
        collections.addAyah(study, 3, 18, 1)
        val type = syncTypes(database).single { it.name == "COLLECTION_AYAH" }
        val (membership, base) = type.readAll().entries.single()
        store.put("COLLECTION_AYAH", SyncRow(membership, "remote-1", base, "422 ValidationError", base))
        collections.removeAyah(study, 3, 18)

        assertEquals(
            listOf(item(SyncResourceType.COLLECTION_BOOKMARK, "3:18", HeldReason.REJECTED_BY_SERVER, "$study", null, 422, "ValidationError")),
            held.read().held
        )
    }

    /** Collectors share one reader; once it has stopped, a later collector gets a fresh read, which shows a wipe. */
    @Test
    fun `collectors share the flow and a later one reads again once the reader stopped`() = runTest {
        val short = notes.addNote("12345", listOf(NoteRange(1, 1, 1, 1)), 0)
        hold("NOTE", short, "BODY_LENGTH")
        val expected = listOf(item(SyncResourceType.NOTE, "$short", HeldReason.TOO_SHORT))
        suspend fun first() = withContext(Dispatchers.Default) { withTimeout(5_000) { held.flow.first() } }
        val values = Channel<List<HeldSyncItem>>(Channel.UNLIMITED)
        val collectors = List(2) { launch(Dispatchers.Default) { held.flow.collect { values.send(it) } } }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { assertEquals(listOf(expected, expected), List(2) { values.receive() }) }
        }
        assertEquals(expected, first(), "a third collector joins the same list")

        collectors.forEach { it.cancelAndJoin() }
        withContext(Dispatchers.Default) {
            // The last collector left: the reader stops and drops the list it read.
            withTimeout(5_000) { while ((held.snapshots as SharedFlow<*>).replayCache.isNotEmpty()) yield() }
        }
        SyncEngineV2.wipe(database, syncTypes(database))

        assertEquals(emptyList(), first(), "read again, not the list from before the wipe")
    }

    /** Held and pending come from one snapshot: a fix moves the note from one to the other in a single emission. */
    @Test
    fun `a fixed held note moves to pending in the same snapshot`() = runTest {
        val short = notes.addNote("12345", listOf(NoteRange(1, 1, 1, 1)), 0)
        hold("NOTE", short, "BODY_LENGTH")
        val values = Channel<UnsyncedSnapshot>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { held.snapshots.collect { values.send(it) } }
        suspend fun next() = withContext(Dispatchers.Default) { withTimeout(5_000) { values.receive() } }
        try {
            assertEquals(UnsyncedSnapshot(listOf(item(SyncResourceType.NOTE, "$short", HeldReason.TOO_SHORT)), 0), next())

            notes.updateNote(short, "Long enough to sync", 1)

            assertEquals(UnsyncedSnapshot(emptyList(), 1), next(), "held 1 to pending 1, the total unchanged")
        } finally {
            collector.cancelAndJoin()
        }
    }

    /** A membership waiting on its held collection counts as held with it, never as pending; a new note is pending. */
    @Test
    fun `the counts put an item waiting on a held one in held and the rest in pending`() = runTest {
        val study = collections.addCollection("system:study", 0).localId
        collections.addAyah(study, 2, 255, 1)
        hold("COLLECTION", study, "RESERVED_NAME")
        notes.addNote("A note the next push sends", listOf(NoteRange(1, 1, 1, 7)), 2)

        val counts = withContext(Dispatchers.Default) { withTimeout(5_000) { held.unsyncedCounts.first() } }

        assertEquals(UnsyncedItemCounts(pending = 1, held = 2), counts)
        assertEquals(counts.held, held.read().held.size, "held is exactly the held items' list")
    }

    /** A read that fails is skipped: the shared reader survives, crashes nothing, and reads again on the next write. */
    @Test
    fun `a held state that doesn't parse skips a list and the flow keeps going`() = runTest {
        val short = notes.addNote("12345", listOf(NoteRange(1, 1, 1, 1)), 0)
        hold("NOTE", short, "BODY_LENGTH")
        val warnings = Channel<String>(Channel.UNLIMITED)
        val writer = object : LogWriter() {
            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                if (tag == "HeldItems") warnings.trySend("$message ${throwable ?: ""}")
            }
        }
        Logger.addLogWriter(writer)
        val values = Channel<List<HeldSyncItem>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { held.flow.collect { values.send(it) } }
        suspend fun <T> next(channel: Channel<T>) = withContext(Dispatchers.Default) { withTimeout(5_000) { channel.receive() } }
        try {
            assertEquals(listOf(item(SyncResourceType.NOTE, "$short", HeldReason.TOO_SHORT)), next(values))

            database.sync_itemQueries.upsert("NOTE", short, null, null, "BODY_LENGTH", "{\"body\": \"$SECRET", null)

            val warning = next(warnings)
            assertFalse(SECRET in warning, warning)
            store.remove("NOTE", short)
            assertEquals(emptyList(), next(values), "read again on the next write")
            assertNull(uncaught.tryReceive().getOrNull())
        } finally {
            collector.cancel()
            Logger.setLogWriters(Logger.config.logWriterList - writer)
        }
    }

    private companion object {
        const val SECRET = "A private reflection on mercy"
    }
}
