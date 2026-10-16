package com.quran.shared.pipeline

import com.quran.shared.mutations.ServerTextLimit
import com.quran.shared.syncv2.HeldItem
import com.quran.shared.syncv2.SyncRow
import com.quran.shared.syncv2.SyncStore
import com.quran.shared.syncv2.types.CollectionsRepository
import com.quran.shared.syncv2.types.NoteRange
import com.quran.shared.syncv2.types.NotesRepository
import com.quran.shared.syncv2.types.syncTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** [HeldItems]: the engine's held items as the public API shows them, and the reason mapping. */
class HeldItemsTest {
    private val database = inMemoryMobileSyncDatabase().synced
    private val held = HeldItems(database)
    private val store = SyncStore(database)
    private val notes = NotesRepository(database)
    private val collections = CollectionsRepository(database)

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
            held.read()
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
            held.read()
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
        val tooShortBody = HeldText("Hi", ServerTextLimit.NOTE_BODY)
        assertEquals(
            listOf(
                PublicHold(HeldReason.TOO_SHORT),
                PublicHold(HeldReason.TOO_LONG),
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
                map("BODY_LENGTH", tooShortBody),
                map("BODY_LENGTH", HeldText("a".repeat(10_001), ServerTextLimit.NOTE_BODY)),
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
            held.read()
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
            held.read()
        )
    }

    @Test
    fun `the flow drops a held note as soon as an edit fixes it`() = runTest {
        val short = notes.addNote("12345", listOf(NoteRange(1, 1, 1, 1)), 0)
        hold("NOTE", short, "BODY_LENGTH")
        val values = Channel<List<HeldSyncItem>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { held.flow.collect { values.send(it) } }
        suspend fun next() = withContext(Dispatchers.Default) { withTimeout(5_000) { values.receive() } }
        try {
            assertEquals(listOf(item(SyncResourceType.NOTE, "$short", HeldReason.TOO_SHORT)), next())

            notes.updateNote(short, "Long enough now", 1)

            assertEquals(emptyList(), next(), "no sync in between")
        } finally {
            collector.cancel()
        }
    }
}
